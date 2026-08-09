#!/usr/bin/env python3
"""Build and verify the Chinese enterprise multi-format RAG dataset."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import contextlib
import dataclasses
import hashlib
import html
from html.parser import HTMLParser
import http.client
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import time
from typing import Any, Callable
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
DATASET_ID = "chinese-enterprise-rag-v1"
DEFAULT_OUTPUT = ROOT / "data" / DATASET_ID
DEFAULT_CACHE = ROOT / "tmp" / "dataset-cache" / DATASET_ID
SELECTION_PATH = ROOT / "benchmarks" / f"{DATASET_ID}.sources.json"
BLUEPRINT_PATH = ROOT / "benchmarks" / f"{DATASET_ID}.blueprint.json"
KNOWLEDGE_BASE_NAME = "中文企业技术知识库 v1"
MIN_CHINESE_CHARS = 1_200
EXPECTED_DOCUMENTS = 200
EXPECTED_CASES = 440
FORMAT_MIME = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "html": "text/html",
    "md": "text/markdown",
}
FORMAT_MATRIX = {
    "openeuler": {"pdf": 13, "docx": 13, "html": 12, "md": 12},
    "kubernetes": {"pdf": 12, "docx": 13, "html": 13, "md": 12},
    "ant-design": {"pdf": 12, "docx": 12, "html": 13, "md": 13},
    "apache-doris": {"pdf": 13, "docx": 12, "html": 12, "md": 13},
}


@dataclasses.dataclass(frozen=True)
class Source:
    key: str
    prefix: str
    repository: str
    commit: str
    license_id: str
    domain: str
    include_prefix: str
    license_output: str
    archive_mode: bool = False
    minimum_chinese_chars: int = MIN_CHINESE_CHARS

    @property
    def archive_url(self) -> str:
        return f"https://codeload.github.com/{self.repository}/tar.gz/{self.commit}"

    @property
    def source_base_url(self) -> str:
        return f"https://github.com/{self.repository}/blob/{self.commit}/"


SOURCES = (
    Source(
        "openeuler", "oe", "openeuler-mirror/docs",
        "ba94cbf9519a075de90203d446a03a05eee14f80", "CC-BY-SA-4.0",
        "企业运维与操作系统", "docs/zh/docs/", "openeuler-CC-BY-SA-4.0.txt",
        True,
    ),
    Source(
        "kubernetes", "k8s", "kubernetes/website",
        "5e1d1bde0ca03efe09608d59c573d6ec87052c24", "CC-BY-4.0",
        "云原生与集群管理", "content/zh-cn/docs/", "kubernetes-CC-BY-4.0.txt",
    ),
    Source(
        "ant-design", "antd", "ant-design/ant-design",
        "f7459dca248ca001777d99a774164d14112d9071", "MIT",
        "企业产品与交互组件", "components/", "ant-design-MIT.txt",
        False, 950,
    ),
    Source(
        "apache-doris", "doris", "apache/doris-website",
        "a818ca0af26d13587733675735e3bbb32fb3dd15", "Apache-2.0",
        "数据平台与分析", "i18n/zh-CN/docusaurus-plugin-content-docs/current/",
        "apache-doris-Apache-2.0.txt",
    ),
)


@dataclasses.dataclass
class Candidate:
    source: Source
    path: str
    raw: bytes
    source_sha256: str
    title: str
    markdown: str
    chinese_chars: int
    category: str
    evidence: list[tuple[str, str]]


class TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.ignored = 0

    def handle_starttag(self, tag: str, _attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"script", "style", "noscript", "template", "nav"}:
            self.ignored += 1
        elif tag in {"p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "pre"}:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "noscript", "template", "nav"} and self.ignored:
            self.ignored -= 1
        elif not self.ignored and tag in {"p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "pre"}:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self.ignored:
            self.parts.append(data)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def chinese_count(value: str) -> int:
    return len(re.findall(r"[\u3400-\u4dbf\u4e00-\u9fff]", value))


def normalize_space(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def comparison_text(value: str) -> str:
    value = html.unescape(value)
    value = re.sub(r"(?<![\w])\d+[.)](?=\s)", "", value)
    return re.sub(r"[^0-9a-zA-Z\u3400-\u4dbf\u4e00-\u9fff]+", "", value).lower()


def run(command: list[str], *, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            cwd=cwd,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout or "").strip()
        raise RuntimeError(f"Command failed: {' '.join(command)}\n{detail}") from exc


def require_tools(*names: str) -> None:
    missing = [name for name in names if shutil.which(name) is None]
    if missing:
        raise RuntimeError(f"Required commands are missing: {', '.join(missing)}")


def download(url: str, destination: Path, *, offline: bool, announce: bool = True) -> None:
    if destination.is_file() and destination.stat().st_size > 0:
        return
    if offline:
        raise RuntimeError(f"Offline cache is missing: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    for attempt in range(1, 4):
        try:
            if announce:
                print(f"Downloading {url} (attempt {attempt}/3)", flush=True)
            headers = {"User-Agent": "yanyue-rag-dataset-builder/1.0"}
            if token := os.getenv("GITHUB_TOKEN"):
                headers["Authorization"] = f"Bearer {token}"
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output, length=1024 * 1024)
            temporary.replace(destination)
            return
        except (OSError, urllib.error.URLError, http.client.HTTPException) as exc:
            temporary.unlink(missing_ok=True)
            if attempt == 3:
                raise RuntimeError(f"Unable to download {url}: {exc}") from exc
            time.sleep(attempt * 2)


def clean_markdown(raw_text: str, path: str) -> tuple[str, str]:
    text = raw_text.replace("\r\n", "\n").replace("\r", "\n").lstrip("\ufeff")
    frontmatter: dict[str, str] = {}
    if text.startswith("---\n"):
        end = text.find("\n---\n", 4)
        if end >= 0:
            for line in text[4:end].splitlines():
                match = re.match(r"^([A-Za-z][\w-]*):\s*[\"']?(.*?)[\"']?\s*$", line)
                if match:
                    frontmatter[match.group(1).lower()] = match.group(2).strip()
            text = text[end + 5 :]

    text = _transform_outside_markdown_code(text, _clean_mdx_prose)
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()

    first_heading = re.search(r"^#\s+(.+?)\s*$", text, flags=re.M)
    first_heading_title = (
        re.sub(r"\s*\{#[\w:.-]+\}\s*$", "", first_heading.group(1)).strip()
        if first_heading
        else ""
    )
    subtitle = frontmatter.get("subtitle", "")
    configured_title = frontmatter.get("title", "")
    if subtitle and chinese_count(subtitle):
        title = f"{subtitle}（{configured_title}）" if configured_title and configured_title != subtitle else subtitle
    elif configured_title:
        title = configured_title
    elif first_heading:
        title = first_heading_title
    else:
        title = Path(path).stem.replace(".zh-CN", "").replace("-", " ")
    title = normalize_space(re.sub(r"[`*_#]", "", title)).strip(" -|：:")
    if not title:
        title = Path(path).stem
    if not first_heading or normalize_space(first_heading_title) != title:
        text = f"# {title}\n\n{text}"
    return title, text.strip() + "\n"


_MARKDOWN_FENCE = re.compile(
    r"(?P<fenced>^[ \t]*(?P<fence>`{3,}|~{3,})[^\n]*\n.*?^[ \t]*(?P=fence)[ \t]*$)",
    flags=re.M | re.S,
)
_MARKDOWN_INLINE_CODE = re.compile(r"(?P<inline>(?P<ticks>`+)[^\n]*?(?P=ticks))")


def _transform_outside_matches(
    text: str,
    protected: re.Pattern[str],
    transform: Callable[[str], str],
) -> str:
    output: list[str] = []
    cursor = 0
    for match in protected.finditer(text):
        output.append(transform(text[cursor : match.start()]))
        output.append(match.group(0))
        cursor = match.end()
    output.append(transform(text[cursor:]))
    return "".join(output)


def _transform_outside_markdown_code(text: str, transform: Callable[[str], str]) -> str:
    """Apply MDX cleanup without changing fenced code blocks."""
    return _transform_outside_matches(text, _MARKDOWN_FENCE, transform)


def _clean_mdx_prose(text: str) -> str:
    # Remove document-level constructs before protecting inline code. Comments often
    # contain backticks, but the entire comment is non-content and must disappear.
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"^\s*(?:import|export)\s+.*$", "", text, flags=re.M)
    text = re.sub(r"\{\{[%<].*?[%>]\}\}", "", text, flags=re.S)
    text = _normalize_html_breaks(text)
    return _transform_outside_matches(text, _MARKDOWN_INLINE_CODE, _clean_mdx_non_code)


def _normalize_html_breaks(text: str) -> str:
    lines: list[str] = []
    for raw_line in text.splitlines(keepends=True):
        line = raw_line[:-1] if raw_line.endswith("\n") else raw_line
        line_ending = "\n" if raw_line.endswith("\n") else ""
        # A hard break inside a GFM table cell is inline content. Turning it into
        # an empty line terminates the table and makes Pandoc flatten the rest.
        table_row = line.lstrip().startswith("|") and len(re.findall(r"(?<!\\)\|", line)) >= 2
        replacement = " " if table_row else "\n\n"
        normalized = (
            _transform_outside_matches(
                line,
                _MARKDOWN_INLINE_CODE,
                lambda value: re.sub(r"<br\s*/?>", replacement, value, flags=re.I),
            )
        )
        lines.append(normalized + line_ending)
    return "".join(lines)


def _clean_mdx_non_code(text: str) -> str:
    text = re.sub(r"<code\s+src=.*?</code>|<code\s+src=[^>]+/?>", "", text, flags=re.I | re.S)
    text = re.sub(r"</?[A-Z][A-Za-z0-9_.:-]*(?:\s+[^<>]*?)?/?>", "", text)
    text = re.sub(
        r"<(?:script|style|nav|template)\b.*?</(?:script|style|nav|template)>",
        "",
        text,
        flags=re.I | re.S,
    )
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(
        r"!\[([^]]*)\]\([^)]*\)",
        lambda match: f"图片说明：{match.group(1)}" if match.group(1).strip() else "",
        text,
    )
    text = re.sub(r"\[\[([^]]+)\]\s*([^]]*)\]\([^)]*\)", r"\1 \2", text)
    text = re.sub(r"\[([^]]+)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"^\s*\[(?:TOC|目录)\]\s*$", "", text, flags=re.I | re.M)
    text = re.sub(r"^\s*(?:Table of Contents|目录)\s*$", "", text, flags=re.I | re.M)
    return html.unescape(text)


def evidence_blocks(markdown: str) -> list[tuple[str, str]]:
    heading = "文档说明"
    results: list[tuple[str, str]] = []
    in_code = False
    for block in re.split(r"\n\s*\n", markdown):
        value = block.strip()
        if not value:
            continue
        if value.startswith("```"):
            in_code = not (value.count("```") % 2 == 0)
            continue
        if in_code:
            continue
        match = re.match(r"^#{1,6}\s+(.+)$", value)
        if match:
            heading = normalize_space(re.sub(r"[`*_]", "", match.group(1)))
            continue
        if value.startswith("|") or value.count("|") > 8 or value.startswith("图片说明："):
            continue
        if chinese_count(value) < 35:
            continue
        compact = normalize_space(value)
        boundary = -1
        for match in re.finditer(r"[。！？；]", compact[:221]):
            if chinese_count(compact[: match.end()]) >= 35:
                boundary = match.end()
                break
        if boundary > 0:
            compact = compact[:boundary]
        elif len(compact) > 220:
            word_boundary = compact.rfind(" ", 120, 221)
            compact = compact[: word_boundary if word_boundary > 0 else 220]
        if chinese_count(compact) >= 35:
            results.append((heading, compact))
    unique: list[tuple[str, str]] = []
    seen: set[str] = set()
    for item in results:
        key = comparison_text(item[1])
        if key not in seen:
            seen.add(key)
            unique.append(item)
    return unique


def source_member_path(member_name: str) -> str:
    parts = member_name.split("/", 1)
    return parts[1] if len(parts) == 2 else member_name


def accepts_path(source: Source, path: str) -> bool:
    lower = path.lower()
    if not lower.endswith((".md", ".mdx")):
        return False
    if source.key == "ant-design":
        return (
            re.fullmatch(r"components/[^/]+/index\.zh-cn\.md", lower) is not None
            or (lower.startswith("docs/") and lower.endswith(".zh-cn.md"))
        )
    if not path.startswith(source.include_prefix):
        return False
    excluded = (
        "/_index.md", "/readme.md", "/contribute/", "/releasenotes/", "/release-notes/",
        "/menu/", "/test.md", "/changelog", "/migration-guide/",
    )
    return not any(token in lower for token in excluded)


def candidate_category(source: Source, path: str) -> str:
    relative = path[len(source.include_prefix) :] if path.startswith(source.include_prefix) else path
    parts = [part for part in relative.split("/") if part]
    if source.key == "ant-design" and parts and parts[0] == "components" and len(parts) > 1:
        return parts[1]
    return parts[0] if len(parts) > 1 else "general"


def load_candidates(source: Source, archive: Path) -> tuple[list[Candidate], bytes]:
    candidates: list[Candidate] = []
    license_text = b""
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar:
            if not member.isfile() or member.size <= 0 or member.size > 4 * 1024 * 1024:
                continue
            path = source_member_path(member.name)
            if path == "LICENSE" and not license_text:
                extracted = tar.extractfile(member)
                license_text = extracted.read() if extracted else b""
                continue
            if not accepts_path(source, path):
                continue
            extracted = tar.extractfile(member)
            if extracted is None:
                continue
            raw = extracted.read()
            try:
                decoded = raw.decode("utf-8-sig")
            except UnicodeDecodeError:
                continue
            if candidate := candidate_from_raw(source, path, raw, decoded):
                candidates.append(candidate)
    if not license_text:
        raise RuntimeError(f"{source.repository} archive does not contain a root LICENSE")
    print(f"{source.key}: {len(candidates)} eligible documents", flush=True)
    return candidates, license_text


def candidate_from_raw(
    source: Source,
    path: str,
    raw: bytes,
    decoded: str | None = None,
) -> Candidate | None:
    if decoded is None:
        try:
            decoded = raw.decode("utf-8-sig")
        except UnicodeDecodeError:
            return None
    title, markdown = clean_markdown(decoded, path)
    count = chinese_count(markdown)
    evidence = evidence_blocks(markdown)
    if count < source.minimum_chinese_chars or len(evidence) < 2:
        return None
    return Candidate(
        source=source,
        path=path,
        raw=raw,
        source_sha256=sha256_bytes(raw),
        title=title,
        markdown=markdown,
        chinese_chars=count,
        category=candidate_category(source, path),
        evidence=evidence,
    )


def tree_candidate_paths(source: Source, tree: dict[str, Any], limit: int = 220) -> list[str]:
    if tree.get("truncated"):
        raise RuntimeError(f"GitHub returned a truncated tree for {source.repository}")
    groups: dict[str, list[dict[str, Any]]] = {}
    for item in tree.get("tree", []):
        path = str(item.get("path", ""))
        size = int(item.get("size") or 0)
        if item.get("type") != "blob" or not accepts_path(source, path) or not 3_500 <= size <= 4 * 1024 * 1024:
            continue
        groups.setdefault(candidate_category(source, path), []).append(item)
    for values in groups.values():
        values.sort(key=lambda item: (-int(item.get("size") or 0), str(item["path"])))
    selected: list[str] = []
    while len(selected) < limit:
        progress = False
        for category in sorted(groups):
            if groups[category]:
                selected.append(str(groups[category].pop(0)["path"]))
                progress = True
                if len(selected) == limit:
                    break
        if not progress:
            break
    return selected


def load_candidates_from_tree(source: Source, cache_dir: Path, *, offline: bool) -> tuple[list[Candidate], bytes]:
    tree_path = cache_dir / f"{source.key}-{source.commit}.tree.json"
    tree_url = f"https://api.github.com/repos/{source.repository}/git/trees/{source.commit}?recursive=1"
    download(tree_url, tree_path, offline=offline)
    tree = json.loads(tree_path.read_text(encoding="utf-8"))
    if "tree" not in tree:
        raise RuntimeError(f"GitHub tree response is invalid for {source.repository}: {tree}")
    paths = tree_candidate_paths(source, tree)
    if len(paths) < 50:
        raise RuntimeError(f"GitHub tree exposed only {len(paths)} candidate paths for {source.repository}")
    file_root = cache_dir / source.key / "files"

    def fetch(path: str) -> tuple[str, bytes]:
        destination = file_root / path
        url = f"https://raw.githubusercontent.com/{source.repository}/{source.commit}/{path}"
        download(url, destination, offline=offline, announce=False)
        return path, destination.read_bytes()

    print(f"{source.key}: downloading {len(paths)} prefiltered Markdown files", flush=True)
    with ThreadPoolExecutor(max_workers=12) as executor:
        downloaded = list(executor.map(fetch, paths))
    candidates = [
        candidate
        for path, raw in downloaded
        if (candidate := candidate_from_raw(source, path, raw)) is not None
    ]
    license_path = cache_dir / source.key / "LICENSE"
    license_url = f"https://raw.githubusercontent.com/{source.repository}/{source.commit}/LICENSE"
    download(license_url, license_path, offline=offline, announce=False)
    print(f"{source.key}: {len(candidates)} eligible documents", flush=True)
    return candidates, license_path.read_bytes()


def balanced_select(candidates: list[Candidate], count: int) -> list[Candidate]:
    groups: dict[str, list[Candidate]] = {}
    for candidate in candidates:
        groups.setdefault(candidate.category, []).append(candidate)
    for values in groups.values():
        values.sort(key=lambda value: (-value.chinese_chars, value.path))
    selected: list[Candidate] = []
    titles: set[str] = set()
    content_hashes: set[str] = set()
    while len(selected) < count:
        progress = False
        for category in sorted(groups):
            while groups[category]:
                candidate = groups[category].pop(0)
                title_key = comparison_text(candidate.title)
                content_key = sha256_bytes(comparison_text(candidate.markdown).encode())
                if title_key in titles or content_key in content_hashes:
                    continue
                selected.append(candidate)
                titles.add(title_key)
                content_hashes.add(content_key)
                progress = True
                break
            if len(selected) == count:
                break
        if not progress:
            break
    if len(selected) != count:
        raise RuntimeError(f"Only {len(selected)} unique eligible documents were available; need {count}")
    return selected


def interleaved_formats(counts: dict[str, int]) -> list[str]:
    remaining = dict(counts)
    result: list[str] = []
    while any(remaining.values()):
        for document_format in ("pdf", "docx", "html", "md"):
            if remaining[document_format] > 0:
                result.append(document_format)
                remaining[document_format] -= 1
    return result


def create_selection(all_candidates: dict[str, list[Candidate]]) -> dict[str, Any]:
    documents: list[dict[str, Any]] = []
    global_titles: set[str] = set()
    for source in SOURCES:
        selected = balanced_select(all_candidates[source.key], 50)
        formats = interleaved_formats(FORMAT_MATRIX[source.key])
        for index, (candidate, document_format) in enumerate(zip(selected, formats, strict=True), 1):
            alias = f"{source.prefix}-{index:03d}"
            title = candidate.title
            title_key = comparison_text(title)
            if title_key in global_titles:
                title = f"{title}（{source.key}）"
                title_key = comparison_text(title)
            global_titles.add(title_key)
            documents.append({
                "alias": alias,
                "title": title,
                "sourceTitle": candidate.title,
                "sourceProject": source.key,
                "sourceRepository": source.repository,
                "sourcePath": candidate.path,
                "sourceUrl": source.source_base_url + candidate.path,
                "upstreamCommit": source.commit,
                "license": source.license_id,
                "domain": source.domain,
                "format": document_format,
                "mimeType": FORMAT_MIME[document_format],
                "sourceSha256": candidate.source_sha256,
            })
    return {
        "schemaVersion": "chinese-enterprise-rag-sources/v1",
        "datasetId": DATASET_ID,
        "documentCount": len(documents),
        "minimumChineseCharacters": min(source.minimum_chinese_chars for source in SOURCES),
        "minimumChineseCharactersBySource": {
            source.key: source.minimum_chinese_chars for source in SOURCES
        },
        "documents": documents,
    }


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def load_selection(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schemaVersion") != "chinese-enterprise-rag-sources/v1":
        raise RuntimeError(f"Unsupported source selection schema in {path}")
    if value.get("datasetId") != DATASET_ID or len(value.get("documents", [])) != EXPECTED_DOCUMENTS:
        raise RuntimeError(f"Source selection must contain {EXPECTED_DOCUMENTS} {DATASET_ID} documents")
    return value


def candidate_index(all_candidates: dict[str, list[Candidate]]) -> dict[tuple[str, str], Candidate]:
    return {
        (candidate.source.key, candidate.path): candidate
        for values in all_candidates.values()
        for candidate in values
    }


def resolve_selection(selection: dict[str, Any], all_candidates: dict[str, list[Candidate]]) -> list[tuple[dict[str, Any], Candidate]]:
    index = candidate_index(all_candidates)
    result: list[tuple[dict[str, Any], Candidate]] = []
    for entry in selection["documents"]:
        key = (entry["sourceProject"], entry["sourcePath"])
        candidate = index.get(key)
        if candidate is None:
            raise RuntimeError(f"Selected source is no longer eligible or present: {key}")
        if candidate.source_sha256 != entry["sourceSha256"]:
            raise RuntimeError(f"Selected source hash changed: {entry['sourcePath']}")
        if candidate.title != entry.get("sourceTitle", entry["title"]):
            raise RuntimeError(f"Selected source title changed: {entry['sourcePath']}")
        result.append((entry, candidate))
    return result


def pandoc_version() -> str:
    return run(["pandoc", "--version"]).stdout.splitlines()[0]


def libreoffice_version() -> str:
    return run(["libreoffice", "--version"]).stdout.strip()


def apply_docx_fonts(path: Path) -> None:
    namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    temporary = path.with_suffix(".font.docx")
    with zipfile.ZipFile(path, "r") as source, zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as target:
        for item in source.infolist():
            data = source.read(item.filename)
            if item.filename == "word/styles.xml":
                tree = ET.fromstring(data)
                for fonts in tree.iter(f"{{{namespace}}}rFonts"):
                    for attribute in ("ascii", "hAnsi", "eastAsia", "cs"):
                        fonts.set(f"{{{namespace}}}{attribute}", "Noto Sans CJK SC")
                    for attribute in ("asciiTheme", "hAnsiTheme", "eastAsiaTheme", "cstheme"):
                        fonts.attrib.pop(f"{{{namespace}}}{attribute}", None)
                data = ET.tostring(tree, encoding="utf-8", xml_declaration=True)
            target.writestr(item, data)
    temporary.replace(path)


def markdown_table_cells(line: str) -> list[str]:
    value = line.strip().strip("|")
    return [cell.replace("\\|", "|").strip() for cell in re.split(r"(?<!\\)\|", value)]


def linearize_markdown_tables(markdown: str) -> str:
    lines = markdown.splitlines()
    result: list[str] = []
    index = 0
    while index < len(lines):
        if index + 1 >= len(lines) or "|" not in lines[index]:
            result.append(lines[index])
            index += 1
            continue
        separator = markdown_table_cells(lines[index + 1])
        if not separator or not all(re.fullmatch(r":?-{3,}:?", cell) for cell in separator):
            result.append(lines[index])
            index += 1
            continue
        headers = markdown_table_cells(lines[index])
        index += 2
        rows: list[list[str]] = []
        while index < len(lines) and "|" in lines[index] and lines[index].strip():
            rows.append(markdown_table_cells(lines[index]))
            index += 1
        for row in rows:
            fields = [
                f"{headers[column]}：{row[column]}"
                for column in range(min(len(headers), len(row)))
                if headers[column] and row[column]
            ]
            if fields:
                result.append("- " + "；".join(fields))
        result.append("")
    return "\n".join(result).strip() + "\n"


ADMONITION_LABELS = {
    "caution": "注意",
    "danger": "警告",
    "info": "说明",
    "note": "说明",
    "tip": "提示",
    "warning": "警告",
}


def normalize_docusaurus_admonitions(markdown: str) -> str:
    """Convert Docusaurus admonitions into Pandoc-compatible block quotes."""
    lines = markdown.splitlines()
    result: list[str] = []
    admonition: tuple[str, str] | None = None
    fence: tuple[str, int] | None = None

    for line in lines:
        stripped = line.lstrip()
        fence_match = re.match(r"^(`{3,}|~{3,})", stripped)
        if fence_match:
            marker = fence_match.group(1)
            marker_key = marker[0]
            if fence is None:
                fence = (marker_key, len(marker))
            elif marker_key == fence[0] and len(marker) >= fence[1]:
                fence = None

        if fence is None and admonition is None:
            opener = re.fullmatch(r"(\s*):::(\w+)(?:\s+(.+?))?\s*", line)
            if opener:
                kind = opener.group(2).lower()
                title = (opener.group(3) or "").strip()
                label = title or ADMONITION_LABELS.get(kind, kind.capitalize())
                admonition = (opener.group(1), kind)
                result.extend([f"{opener.group(1)}> **{label}**", f"{opener.group(1)}>"])
                continue

        if fence is None and admonition is not None and re.fullmatch(r"\s*:::\s*", line):
            admonition = None
            result.append("")
            continue

        if admonition is not None:
            base_indent, _kind = admonition
            body = line[len(base_indent):] if line.startswith(base_indent) else line
            result.append(f"{base_indent}> {body}" if body else f"{base_indent}>")
        else:
            result.append(line)

    return "\n".join(result).strip() + "\n"


def prepare_markdown_for_pdf_conversion(markdown: str) -> str:
    return linearize_markdown_tables(normalize_docusaurus_admonitions(markdown))


def strip_heading_anchors(markdown: str) -> str:
    """Remove Docusaurus anchors before converting to formats that render them as text."""
    def strip_from_prose(value: str) -> str:
        return re.sub(
            r"^(#{1,6}\s+.*?)\s*\{#[\w:.-]+\}(\s*#*\s*)$",
            r"\1\2",
            value,
            flags=re.M,
        )

    return _transform_outside_markdown_code(markdown, strip_from_prose)


def convert_document(entry: dict[str, Any], candidate: Candidate, output_root: Path, workspace: Path) -> Path:
    document_format = entry["format"]
    destination = output_root / "corpus" / document_format / f"{entry['alias']}.{document_format}"
    destination.parent.mkdir(parents=True, exist_ok=True)
    markdown_path = workspace / f"{entry['alias']}.md"
    conversion_source = candidate.markdown if document_format == "md" else strip_heading_anchors(candidate.markdown)
    conversion_markdown = (
        prepare_markdown_for_pdf_conversion(conversion_source)
        if document_format == "pdf"
        else conversion_source
    )
    markdown_path.write_text(conversion_markdown, encoding="utf-8")
    if document_format == "md":
        shutil.copy2(markdown_path, destination)
    elif document_format == "html":
        run([
            "pandoc", "--from=gfm", "--to=html5", "--standalone", "--wrap=none",
            "--metadata", f"title={entry['title']}", "--output", str(destination), str(markdown_path),
        ])
        rendered = destination.read_text(encoding="utf-8")
        rendered = re.sub(
            r"<(?:script|style|nav|template)\b.*?</(?:script|style|nav|template)>",
            "",
            rendered,
            flags=re.I | re.S,
        )
        destination.write_text(rendered, encoding="utf-8")
    else:
        docx_path = destination if document_format == "docx" else workspace / f"{entry['alias']}.docx"
        run([
            "pandoc", "--from=gfm", "--to=docx", "--metadata", f"title={entry['title']}",
            "--output", str(docx_path), str(markdown_path),
        ])
        apply_docx_fonts(docx_path)
        if document_format == "pdf":
            profile = workspace / f"lo-profile-{entry['alias']}"
            run([
                "libreoffice", f"-env:UserInstallation={profile.resolve().as_uri()}", "--headless",
                "--convert-to", "pdf", "--outdir", str(destination.parent), str(docx_path),
            ])
            generated = destination.parent / f"{entry['alias']}.pdf"
            if not generated.is_file():
                raise RuntimeError(f"LibreOffice did not produce {generated}")
    if not destination.is_file() or destination.stat().st_size == 0:
        raise RuntimeError(f"Conversion produced no output: {destination}")
    return destination


def expected_answer(evidence: str) -> str:
    value = re.sub(r"^\s*(?:[-+*]|\d+[.)])\s+", "", evidence)
    value = re.sub(r"[`*_]", "", value)
    return normalize_space(value)


def case_metadata(
    *, category: str, difficulty: str, mode: str, no_answer: bool,
    source_format: Any, source_project: str, heading: Any, quote: Any,
) -> dict[str, Any]:
    return {
        "category": category,
        "difficulty": difficulty,
        "recommendedMode": mode,
        "expectNoAnswer": no_answer,
        "sourceFormat": source_format,
        "sourceProject": source_project,
        "evidenceHeading": heading,
        "evidenceQuote": quote,
    }


def build_blueprint(resolved: list[tuple[dict[str, Any], Candidate]]) -> dict[str, Any]:
    cases: list[dict[str, Any]] = []
    by_source: dict[str, list[tuple[dict[str, Any], Candidate]]] = {}
    for entry, candidate in resolved:
        by_source.setdefault(entry["sourceProject"], []).append((entry, candidate))
        first, second = candidate.evidence[:2]
        cases.append({
            "question": f"根据《{entry['title']}》，{first[0]}部分给出的关键信息是什么？",
            "expectedAnswer": expected_answer(first[1]),
            "expectedDocuments": [entry["alias"]],
            "metadata": case_metadata(
                category="direct_fact", difficulty="single-hop", mode="FAST", no_answer=False,
                source_format=entry["format"], source_project=entry["sourceProject"],
                heading=first[0], quote=first[1],
            ),
        })
        cases.append({
            "question": f"在《{entry['title']}》的“{second[0]}”部分，文档具体说明了什么要求或做法？",
            "expectedAnswer": expected_answer(second[1]),
            "expectedDocuments": [entry["alias"]],
            "metadata": case_metadata(
                category="procedure_condition", difficulty="single-hop", mode="FAST", no_answer=False,
                source_format=entry["format"], source_project=entry["sourceProject"],
                heading=second[0], quote=second[1],
            ),
        })

    for source in SOURCES:
        values = by_source[source.key]
        for pair_index in range(6):
            left_entry, left = values[pair_index * 2]
            right_entry, right = values[pair_index * 2 + 1]
            left_evidence = left.evidence[0]
            right_evidence = right.evidence[0]
            cases.append({
                "question": (
                    f"综合《{left_entry['title']}》与《{right_entry['title']}》，分别概括两份文档"
                    f"在“{left_evidence[0]}”和“{right_evidence[0]}”部分说明的关键信息。"
                ),
                "expectedAnswer": (
                    f"《{left_entry['title']}》：{expected_answer(left_evidence[1])} "
                    f"《{right_entry['title']}》：{expected_answer(right_evidence[1])}"
                ),
                "expectedDocuments": [left_entry["alias"], right_entry["alias"]],
                "metadata": case_metadata(
                    category="cross_document", difficulty="multi-hop", mode="DEEP", no_answer=False,
                    source_format=[left_entry["format"], right_entry["format"]],
                    source_project=source.key,
                    heading=[left_evidence[0], right_evidence[0]],
                    quote=[left_evidence[1], right_evidence[1]],
                ),
            })

    no_answer_questions = {
        "openeuler": [
            "现有 openEuler 运维文档是否给出了商业上门支持的年度报价？",
            "知识库是否列出了 openEuler 专属硬件设备的保修序列号？",
            "文档是否提供了厂商紧急升级电话及值班人员排班表？",
            "当前资料是否规定了购买商业培训席位的折扣比例？",
        ],
        "kubernetes": [
            "现有 Kubernetes 文档是否给出了某家托管云厂商的集群月度费用？",
            "知识库是否提供了 Kubernetes 现场运维工程师的排班表？",
            "文档是否规定了第三方厂商专属支持热线的响应 SLA？",
            "当前资料是否包含购买专有 Kubernetes 发行版许可证的报价？",
        ],
        "ant-design": [
            "现有 Ant Design 文档是否给出了企业采购授权的商业报价？",
            "知识库是否列出了客户设计评审服务的承诺响应时间？",
            "文档是否提供了某个客户生产环境的实际部署地址？",
            "当前资料是否规定了付费专属组件的专利赔偿额度？",
        ],
        "apache-doris": [
            "现有 Doris 文档是否给出了商业订阅版的年度价格？",
            "知识库是否提供了 Doris 专属支持工程师的值班排期？",
            "文档是否列出了正在使用 Doris 的全部付费客户名单？",
            "当前资料是否规定了 Doris 一体机硬件的保修期限？",
        ],
    }
    for source_index, source in enumerate(SOURCES):
        for question_index, question in enumerate(no_answer_questions[source.key]):
            cases.append({
                "question": question,
                "expectedAnswer": "当前知识库文档未提供该信息，无法依据现有资料作出确定回答。",
                "expectedDocuments": [],
                "metadata": case_metadata(
                    category="no_answer", difficulty="negative-rejection",
                    mode="FAST" if (source_index + question_index) % 2 == 0 else "DEEP",
                    no_answer=True, source_format="mixed", source_project=source.key,
                    heading="", quote="",
                ),
            })

    for index, case in enumerate(cases, 1):
        case["caseId"] = f"CER-{index:03d}"
    selectors = {
        entry["alias"]: {"title": entry["title"], "requireActive": True}
        for entry, _candidate in resolved
    }
    return {
        "schemaVersion": "rag-evaluation-blueprint/v1",
        "benchmarkId": DATASET_ID,
        "name": "中文企业技术知识库多格式评测集 v1",
        "description": "覆盖200篇公开许可中文企业技术文档的直接事实、操作条件、跨文档综合和无答案拒答。",
        "knowledgeBase": {"name": KNOWLEDGE_BASE_NAME},
        "expectations": {
            "caseCount": len(cases),
            "categories": ["cross_document", "direct_fact", "no_answer", "procedure_condition"],
        },
        "documentSelectors": selectors,
        "cases": cases,
    }


def extract_docx_text(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        xml = ET.fromstring(archive.read("word/document.xml"))
    return "\n".join(value for element in xml.iter() if element.tag.endswith("}t") and (value := element.text))


def extract_html_text(path: Path) -> str:
    parser = TextExtractor()
    parser.feed(path.read_text(encoding="utf-8"))
    return "".join(parser.parts)


def extract_document_text(path: Path, document_format: str) -> str:
    if document_format == "md":
        return path.read_text(encoding="utf-8")
    if document_format == "html":
        return extract_html_text(path)
    if document_format == "docx":
        return extract_docx_text(path)
    if document_format == "pdf":
        with tempfile.NamedTemporaryFile(suffix=".txt") as output:
            run(["pdftotext", str(path), output.name])
            return Path(output.name).read_text(encoding="utf-8", errors="replace")
    raise RuntimeError(f"Unsupported dataset format: {document_format}")


def validate_blueprint(blueprint: dict[str, Any], manifest: list[dict[str, Any]], output: Path) -> None:
    cases = blueprint.get("cases", [])
    if len(cases) != EXPECTED_CASES:
        raise RuntimeError(f"Expected {EXPECTED_CASES} evaluation cases, found {len(cases)}")
    aliases = {entry["alias"] for entry in manifest}
    single_counts = {alias: 0 for alias in aliases}
    questions: set[str] = set()
    category_counts: dict[str, int] = {}
    extracted: dict[str, str] = {}
    for entry in manifest:
        extracted[entry["alias"]] = comparison_text(extract_document_text(
            output / entry["relativePath"], entry["format"]
        ))
    for case in cases:
        question = normalize_space(case["question"])
        if not question or question in questions:
            raise RuntimeError(f"Duplicate or blank evaluation question: {question}")
        questions.add(question)
        expected = case["expectedDocuments"]
        if any(alias not in aliases for alias in expected):
            raise RuntimeError(f"Evaluation case references an unknown document: {case['caseId']}")
        category = case["metadata"]["category"]
        category_counts[category] = category_counts.get(category, 0) + 1
        if len(expected) == 1 and category in {"direct_fact", "procedure_condition"}:
            single_counts[expected[0]] += 1
        quotes = case["metadata"].get("evidenceQuote")
        quote_values = quotes if isinstance(quotes, list) else [quotes]
        for alias, quote in zip(expected, quote_values, strict=False):
            if quote and comparison_text(str(quote)) not in extracted[alias]:
                raise RuntimeError(f"Evidence is not present in {alias}: {case['caseId']}")
    if any(count != 2 for count in single_counts.values()):
        raise RuntimeError("Every document must have exactly two single-document evaluation cases")
    expected_categories = {
        "direct_fact": 200,
        "procedure_condition": 200,
        "cross_document": 24,
        "no_answer": 16,
    }
    if category_counts != expected_categories:
        raise RuntimeError(f"Unexpected evaluation category counts: {category_counts}")


def validate_dataset(output: Path) -> None:
    manifest_path = output / "metadata" / "manifest.jsonl"
    if not manifest_path.is_file():
        raise RuntimeError(f"Manifest not found: {manifest_path}")
    manifest = [json.loads(line) for line in manifest_path.read_text(encoding="utf-8").splitlines() if line]
    if len(manifest) != EXPECTED_DOCUMENTS:
        raise RuntimeError(f"Expected {EXPECTED_DOCUMENTS} manifest records, found {len(manifest)}")
    aliases = [entry["alias"] for entry in manifest]
    titles = [entry["title"] for entry in manifest]
    paths = [entry["relativePath"] for entry in manifest]
    if len(set(aliases)) != len(aliases) or len(set(titles)) != len(titles) or len(set(paths)) != len(paths):
        raise RuntimeError("Manifest aliases, titles, and paths must be unique")
    format_counts: dict[str, int] = {}
    source_counts: dict[str, int] = {}
    matrix: dict[str, dict[str, int]] = {}
    content_hashes: set[str] = set()
    checksum_lines: list[str] = []
    for entry in manifest:
        path = output / entry["relativePath"]
        if not path.is_file():
            raise RuntimeError(f"Dataset file is missing: {path}")
        actual_hash = sha256_bytes(path.read_bytes())
        if actual_hash != entry["sha256"]:
            raise RuntimeError(f"Dataset hash mismatch: {path}")
        checksum_lines.append(f"{actual_hash}  {entry['relativePath']}")
        document_format = entry["format"]
        if document_format == "pdf" and not path.read_bytes().startswith(b"%PDF-"):
            raise RuntimeError(f"Invalid PDF signature: {path}")
        if document_format == "docx" and not zipfile.is_zipfile(path):
            raise RuntimeError(f"Invalid DOCX archive: {path}")
        if document_format == "html" and re.search(r"<(?:script|style|nav|template)\b", path.read_text(encoding="utf-8"), re.I):
            raise RuntimeError(f"HTML contains an active or navigation element: {path}")
        extracted = extract_document_text(path, document_format)
        if chinese_count(extracted) < 500:
            raise RuntimeError(f"Dataset output has too little Chinese content: {path}")
        content_hash = sha256_bytes(comparison_text(extracted).encode())
        if content_hash in content_hashes:
            raise RuntimeError(f"Duplicate normalized document content: {path}")
        content_hashes.add(content_hash)
        format_counts[document_format] = format_counts.get(document_format, 0) + 1
        source = entry["sourceProject"]
        source_counts[source] = source_counts.get(source, 0) + 1
        matrix.setdefault(source, {})[document_format] = matrix.setdefault(source, {}).get(document_format, 0) + 1
    if format_counts != {"pdf": 50, "docx": 50, "html": 50, "md": 50}:
        raise RuntimeError(f"Unexpected format distribution: {format_counts}")
    if source_counts != {source.key: 50 for source in SOURCES}:
        raise RuntimeError(f"Unexpected source distribution: {source_counts}")
    if matrix != FORMAT_MATRIX:
        raise RuntimeError(f"Unexpected source/format matrix: {matrix}")
    expected_checksums = "\n".join(checksum_lines) + "\n"
    checksum_path = output / "metadata" / "checksums.sha256"
    if checksum_path.read_text(encoding="utf-8") != expected_checksums:
        raise RuntimeError("checksums.sha256 is not synchronized with the manifest")
    blueprint = json.loads((output / "evaluation" / f"{DATASET_ID}.blueprint.json").read_text(encoding="utf-8"))
    validate_blueprint(blueprint, manifest, output)
    print(f"Verified {len(manifest)} documents and {len(blueprint['cases'])} evaluation cases in {output}")


def write_readme(output: Path, manifest: list[dict[str, Any]]) -> None:
    size = sum((output / entry["relativePath"]).stat().st_size for entry in manifest)
    content = f"""# 中文企业技术知识库 v1

该数据集包含 200 篇内容互不重复的公开许可中文企业技术文档，PDF、DOCX、HTML、Markdown 各 50 篇，
以及 440 条带标准答案、目标文档别名和原文证据的 RAG 评测题。

## 组成

| 来源 | PDF | DOCX | HTML | Markdown | 合计 | 许可 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| openEuler | 13 | 13 | 12 | 12 | 50 | CC BY-SA 4.0 |
| Kubernetes | 12 | 13 | 13 | 12 | 50 | CC BY 4.0 |
| Ant Design | 12 | 12 | 13 | 13 | 50 | MIT |
| Apache Doris | 13 | 12 | 12 | 13 | 50 | Apache-2.0 |

语料总大小：{size / 1024 / 1024:.1f} MiB。具体来源、固定提交、原始 URL、许可证和校验和见
`metadata/manifest.jsonl`。转换后的文档继续遵守各自上游许可证，尤其 openEuler 内容仍为
CC BY-SA 4.0。本目录不对全部语料重新授予统一许可证。

## 使用

从项目根目录验证数据集：

```bash
python3 scripts/build-chinese-enterprise-dataset.py --verify-only
```

重新构建：

```bash
python3 scripts/build-chinese-enterprise-dataset.py
```

批量上传并在全部文档成功后导入评测集：

```bash
scripts/import-chinese-enterprise-dataset.sh
```

`corpus/` 中每篇逻辑文档只保留一种格式。评测 blueprint 使用稳定文档别名；导入脚本会在目标知识库中
按唯一标题解析环境相关的文档 UUID。
"""
    (output / "README.md").write_text(content, encoding="utf-8")


def build(args: argparse.Namespace) -> None:
    require_tools("pandoc", "libreoffice", "pdftotext")
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    if args.clean_cache and args.cache_dir.exists():
        shutil.rmtree(args.cache_dir)
        args.cache_dir.mkdir(parents=True)

    all_candidates: dict[str, list[Candidate]] = {}
    licenses: dict[str, bytes] = {}
    for source in SOURCES:
        if source.archive_mode:
            archive = args.cache_dir / f"{source.key}-{source.commit}.tar.gz"
            download(source.archive_url, archive, offline=args.offline)
            candidates, license_text = load_candidates(source, archive)
        else:
            candidates, license_text = load_candidates_from_tree(
                source, args.cache_dir, offline=args.offline
            )
        all_candidates[source.key] = candidates
        licenses[source.key] = license_text

    if args.refresh_selection or not SELECTION_PATH.exists():
        selection = create_selection(all_candidates)
        write_json(SELECTION_PATH, selection)
        print(f"Wrote fixed source selection to {SELECTION_PATH}")
    selection = load_selection(SELECTION_PATH)
    resolved = resolve_selection(selection, all_candidates)
    blueprint = build_blueprint(resolved)
    if len(blueprint["cases"]) != EXPECTED_CASES:
        raise RuntimeError(f"Expected {EXPECTED_CASES} cases, generated {len(blueprint['cases'])}")
    write_json(BLUEPRINT_PATH, blueprint)

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    build_root = Path(tempfile.mkdtemp(prefix=f".{DATASET_ID}-", dir=output.parent))
    workspace = build_root / ".work"
    workspace.mkdir()
    manifest: list[dict[str, Any]] = []
    try:
        tool_versions = {"pandoc": pandoc_version(), "libreoffice": libreoffice_version()}
        for index, (entry, candidate) in enumerate(resolved, 1):
            destination = convert_document(entry, candidate, build_root, workspace)
            relative = destination.relative_to(build_root).as_posix()
            manifest.append({
                **entry,
                "relativePath": relative,
                "fileName": destination.name,
                "byteSize": destination.stat().st_size,
                "sha256": sha256_bytes(destination.read_bytes()),
                "sourceChineseCharacters": candidate.chinese_chars,
                "normalizedTextSha256": sha256_bytes(comparison_text(candidate.markdown).encode()),
                "conversionTools": tool_versions,
            })
            if index % 10 == 0:
                print(f"Converted {index}/{EXPECTED_DOCUMENTS} documents", flush=True)
        shutil.rmtree(workspace)
        metadata = build_root / "metadata"
        metadata.mkdir(parents=True)
        (metadata / "manifest.jsonl").write_text(
            "".join(json.dumps(entry, ensure_ascii=False) + "\n" for entry in manifest), encoding="utf-8"
        )
        (metadata / "checksums.sha256").write_text(
            "".join(f"{entry['sha256']}  {entry['relativePath']}\n" for entry in manifest), encoding="utf-8"
        )
        summary = {
            "schemaVersion": "chinese-enterprise-rag-summary/v1",
            "datasetId": DATASET_ID,
            "documentCount": len(manifest),
            "evaluationCaseCount": len(blueprint["cases"]),
            "formats": {document_format: sum(1 for entry in manifest if entry["format"] == document_format) for document_format in FORMAT_MIME},
            "sources": {source.key: sum(1 for entry in manifest if entry["sourceProject"] == source.key) for source in SOURCES},
            "sourceFormatMatrix": FORMAT_MATRIX,
        }
        write_json(metadata / "source-summary.json", summary)
        evaluation = build_root / "evaluation"
        evaluation.mkdir()
        write_json(evaluation / f"{DATASET_ID}.blueprint.json", blueprint)
        license_dir = build_root / "licenses"
        license_dir.mkdir()
        for source in SOURCES:
            (license_dir / source.license_output).write_bytes(licenses[source.key])
        write_readme(build_root, manifest)
        validate_dataset(build_root)

        backup = output.with_name(output.name + ".previous")
        if backup.exists():
            shutil.rmtree(backup)
        if output.exists():
            output.rename(backup)
        build_root.rename(output)
        if backup.exists():
            shutil.rmtree(backup)
        print(f"Built {DATASET_ID} at {output}")
    except Exception:
        shutil.rmtree(build_root, ignore_errors=True)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--clean-cache", action="store_true")
    parser.add_argument("--refresh-selection", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.verify_only:
        require_tools("pdftotext")
        validate_dataset(args.output.resolve())
        return
    build(args)


if __name__ == "__main__":
    main()
