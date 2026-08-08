from __future__ import annotations

import hashlib
import json
import re
from datetime import UTC, datetime
from email.message import Message
from functools import partial
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlsplit

from anyio import to_thread

from parser_sidecar import __version__
from parser_sidecar.config import Settings
from parser_sidecar.models import (
    NormalizedBlock,
    NormalizedDocument,
    ParseDocumentRequest,
    ParseQualityIssue,
    ParseQualityReport,
    ParseQualityStatus,
)
from parser_sidecar.parsers import BlockDraft, ParsedContent, parse_document
from parser_sidecar.remote import DownloadedObject, RemoteObjectClient

SCHEMA_VERSION = "2.0"
BLOCK_VERSION = "2.0"
NORMALIZED_SEPARATOR = "\n\n"


class ParserService:
    def __init__(self, settings: Settings, remote_client: RemoteObjectClient) -> None:
        self._settings = settings
        self._remote_client = remote_client

    async def parse(self, request: ParseDocumentRequest) -> tuple[NormalizedDocument, bytes]:
        downloaded = await self._remote_client.download(request.source_url)
        source_name = _source_name(request, downloaded)
        content_type = request.content_type or downloaded.content_type

        parsed = await to_thread.run_sync(
            partial(
                parse_document,
                downloaded.content,
                source_name=source_name,
                content_type=content_type,
                parser_profile=request.parser_profile,
                options=request.options,
                max_archive_bytes=self._settings.max_archive_bytes,
            )
        )
        document = _normalize_document(
            downloaded.content,
            source_name=source_name,
            requested_content_type=content_type,
            parser_profile=request.parser_profile,
            parsed=parsed,
            parser_options=request.options,
        )
        payload = document.model_dump_json(by_alias=True).encode("utf-8")
        if request.result_url is not None:
            await self._remote_client.put_json(request.result_url, payload)
        return document, payload


def _normalize_document(
    source_bytes: bytes,
    *,
    source_name: str,
    requested_content_type: str | None,
    parser_profile: str,
    parsed: ParsedContent,
    parser_options: dict[str, Any] | None = None,
) -> NormalizedDocument:
    source_hash = hashlib.sha256(source_bytes).hexdigest()
    blocks, normalized_text = _normalize_blocks(parsed.blocks, source_hash)
    normalized_markdown = _render_normalized_markdown(blocks)
    quality = _assess_quality(blocks, normalized_text, parsed)
    normalized_hash = hashlib.sha256(normalized_text.encode("utf-8")).hexdigest()
    metadata = {
        **parsed.metadata,
        "parserEngine": parsed.engine,
        "parserProfile": parser_profile,
        "parserOptions": dict(parser_options or {}),
        "mediaType": parsed.media_type,
        "requestedContentType": requested_content_type,
        "byteLength": len(source_bytes),
        "hashAlgorithm": "SHA-256",
        "normalizedTextHash": normalized_hash,
        "normalizedTextLength": len(normalized_text),
        "sourceOffsetBasis": "normalizedDocumentText",
        "sourceOffsetUnit": "UTF16_CODE_UNIT",
        "normalizedTextSeparator": NORMALIZED_SEPARATOR,
        "blockSchemaVersion": BLOCK_VERSION,
    }
    return NormalizedDocument(
        schema_version=SCHEMA_VERSION,
        parser_name="parser-sidecar",
        parser_version=__version__,
        title=parsed.title,
        source_name=source_name,
        content_hash=source_hash,
        parsed_at=datetime.now(UTC),
        metadata={key: value for key, value in metadata.items() if value is not None},
        normalized_markdown=normalized_markdown,
        quality=quality,
        blocks=blocks,
    )


def _normalize_blocks(
    drafts: list[BlockDraft],
    source_hash: str,
) -> tuple[list[NormalizedBlock], str]:
    blocks: list[NormalizedBlock] = []
    normalized_parts: list[str] = []
    cursor = 0

    for draft in drafts:
        text = _block_text(draft.text, draft.attributes)
        if not text:
            continue
        order_index = len(blocks)
        text_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
        block_seed = (
            f"{BLOCK_VERSION}\0{source_hash}\0{order_index}\0{draft.type.value}\0{text_hash}"
        )
        block_id = f"blk_v2_{hashlib.sha256(block_seed.encode('utf-8')).hexdigest()[:24]}"
        source_start = cursor
        source_end = source_start + _utf16_length(text)
        attributes = {
            **draft.attributes,
            "blockVersion": BLOCK_VERSION,
            "contentHash": text_hash,
        }
        blocks.append(
            NormalizedBlock(
                block_id=block_id,
                type=draft.type,
                text=text,
                order_index=order_index,
                page_number=draft.page_number,
                heading_path=draft.heading_path,
                bounding_box=draft.bounding_box,
                source_start=source_start,
                source_end=source_end,
                source_offset_unit="UTF16_CODE_UNIT",
                attributes=attributes,
            )
        )
        normalized_parts.append(text)
        cursor = source_end + _utf16_length(NORMALIZED_SEPARATOR)

    return blocks, NORMALIZED_SEPARATOR.join(normalized_parts)


def _render_normalized_markdown(blocks: list[NormalizedBlock]) -> str:
    parts: list[str] = []
    for block in blocks:
        text = _block_text(block.text, block.attributes)
        if not text or block.type.value in {"PAGE_HEADER", "PAGE_FOOTER"}:
            continue
        if block.type.value == "TITLE":
            rendered = f"# {text}"
        elif block.type.value == "HEADING":
            raw_level = block.attributes.get("headingLevel", len(block.heading_path))
            try:
                level = max(2, min(6, int(raw_level)))
            except (TypeError, ValueError):
                level = max(2, min(6, len(block.heading_path)))
            rendered = f"{'#' * level} {text}"
        elif block.type.value == "CODE":
            language = str(block.attributes.get("language", "")).strip()
            rendered = f"```{language}\n{text}\n```"
        elif block.type.value == "LIST":
            rendered = _render_list_markdown(text, block.attributes)
        elif block.type.value == "CAPTION":
            rendered = f"*{text}*"
        else:
            rendered = text
        parts.append(rendered)
    return "\n\n".join(parts)


def _render_list_markdown(value: str, attributes: dict[str, Any]) -> str:
    lines = [line for line in value.splitlines() if line.strip()]
    if attributes.get("structureDetected") == "FEATURE_GATE_LIST":
        rendered: list[str] = []
        for line in lines:
            item = line.strip()
            if re.match(
                r"^(?:kube:)?[A-Za-z][A-Za-z0-9_.:-]*="
                r"(?:true|false)\|(?:true|false)\s*\(",
                item,
                re.IGNORECASE,
            ):
                if rendered and not rendered[-1].startswith("- "):
                    rendered.append("")
                rendered.append(f"- {item}")
            else:
                rendered.append(item)
        return "\n".join(rendered)
    if any(re.match(r"^\s*(?:[-+*•]|\d+[.)])\s+", line) for line in lines):
        return "\n".join(re.sub(r"^(\s*)•\s+", r"\1- ", line) for line in lines)
    return "\n".join(f"- {line.strip()}" for line in lines)


def _block_text(value: str, attributes: dict[str, Any]) -> str:
    if attributes.get("engine") != "markdown":
        return value.strip()
    lines = [line.rstrip(" \t\f\v") for line in value.splitlines()]
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(lines)


def _assess_quality(
    blocks: list[NormalizedBlock],
    normalized_text: str,
    parsed: ParsedContent,
) -> ParseQualityReport:
    issues: list[ParseQualityIssue] = []
    score = 100
    non_space = sum(1 for char in normalized_text if not char.isspace())
    replacement_count = normalized_text.count("\ufffd")
    replacement_ratio = replacement_count / max(1, len(normalized_text))
    content_blocks = [
        block for block in blocks if block.type.value not in {"PAGE_HEADER", "PAGE_FOOTER"}
    ]
    fingerprints = [
        block.attributes.get("contentHash") for block in content_blocks if len(block.text) >= 24
    ]
    duplicate_count = len(fingerprints) - len(set(fingerprints))
    duplicate_ratio = duplicate_count / max(1, len(fingerprints))
    page_count = int(parsed.metadata.get("pageCount", 0) or 0)
    covered_pages = len(
        {block.page_number for block in content_blocks if block.page_number is not None}
    )
    page_coverage = covered_pages / page_count if page_count else 1.0
    text_per_page = non_space / max(1, page_count)
    largest = max((len(block.text) for block in content_blocks), default=0)
    block_type_counts = {
        block_type: sum(1 for block in content_blocks if block.type.value == block_type)
        for block_type in ("TITLE", "HEADING", "PARAGRAPH", "LIST", "TABLE", "CODE", "CAPTION")
    }
    structured_count = sum(
        block_type_counts[block_type]
        for block_type in ("TITLE", "HEADING", "LIST", "TABLE", "CODE", "CAPTION")
    )
    heading_path_count = sum(1 for block in content_blocks if block.heading_path)
    feature_gate_blocks = [
        block
        for block in content_blocks
        if block.attributes.get("structureDetected") == "FEATURE_GATE_LIST"
    ]
    feature_gate_items = sum(
        int(block.attributes.get("itemCount", 0) or 0) for block in feature_gate_blocks
    )
    largest_type = next(
        (block.type.value for block in content_blocks if len(block.text) == largest),
        None,
    )
    pdf_markup_blocks = _pdf_markup_leakage_blocks(content_blocks, parsed.media_type)

    if non_space < 20:
        score -= 80
        issues.append(
            ParseQualityIssue(
                code="CONTENT_TOO_SHORT",
                severity=ParseQualityStatus.FAIL,
                message="解析结果正文过短，无法形成可靠的检索内容。",
            )
        )
    if replacement_ratio >= 0.02:
        score -= 60
        issues.append(
            ParseQualityIssue(
                code="EXCESSIVE_REPLACEMENT_CHARACTERS",
                severity=ParseQualityStatus.FAIL,
                message="正文包含大量无法解码的字符，建议更换解析方式或检查源文件。",
            )
        )
    elif replacement_ratio >= 0.002:
        score -= 20
        issues.append(
            ParseQualityIssue(
                code="REPLACEMENT_CHARACTERS_DETECTED",
                severity=ParseQualityStatus.WARNING,
                message="正文存在少量无法解码的字符，请确认预览内容是否完整。",
            )
        )
    if page_count and (page_coverage < 0.6 or text_per_page < 80):
        score -= 30
        issues.append(
            ParseQualityIssue(
                code="SPARSE_PDF_TEXT_LAYER",
                severity=ParseQualityStatus.WARNING,
                message="PDF 文本层较稀疏，可能包含扫描页；建议检查预览或使用 OCR 重试。",
            )
        )
    if duplicate_ratio >= 0.35:
        score -= 20
        issues.append(
            ParseQualityIssue(
                code="REPEATED_CONTENT",
                severity=ParseQualityStatus.WARNING,
                message="解析结果包含较多重复内容，可能混入页眉、页脚或重复版面。",
            )
        )
    if largest > 50_000:
        score -= 15
        oversized = max(content_blocks, key=lambda block: len(block.text))
        issues.append(
            ParseQualityIssue(
                code="OVERSIZED_BLOCK",
                severity=ParseQualityStatus.WARNING,
                message="存在超大正文块，结构边界可能未被正确识别。",
                block_ids=[oversized.block_id],
            )
        )
    if pdf_markup_blocks:
        score -= 25
        issues.append(
            ParseQualityIssue(
                code="SOURCE_MARKUP_LEAKAGE",
                severity=ParseQualityStatus.WARNING,
                message=(
                    "PDF 正文中残留原始 Markdown/Docusaurus 标记，源文件转换或结构解析可能不完整。"
                ),
                block_ids=[block.block_id for block in pdf_markup_blocks[:20]],
            )
        )

    score = max(0, min(100, score))
    if any(issue.severity == ParseQualityStatus.FAIL for issue in issues):
        status = ParseQualityStatus.FAIL
    elif issues:
        status = ParseQualityStatus.WARNING
    else:
        status = ParseQualityStatus.PASS
    return ParseQualityReport(
        status=status,
        score=score,
        issues=issues,
        metrics={
            "blockCount": len(blocks),
            "contentBlockCount": len(content_blocks),
            "normalizedCharacters": len(normalized_text),
            "nonWhitespaceCharacters": non_space,
            "pageCount": page_count,
            "coveredPages": covered_pages,
            "pageCoverage": round(page_coverage, 4),
            "charactersPerPage": round(text_per_page, 2),
            "replacementCharacterRatio": round(replacement_ratio, 6),
            "duplicateBlockRatio": round(duplicate_ratio, 4),
            "largestBlockCharacters": largest,
            "largestBlockType": largest_type,
            "titleBlockCount": block_type_counts["TITLE"],
            "headingBlockCount": block_type_counts["HEADING"],
            "paragraphBlockCount": block_type_counts["PARAGRAPH"],
            "listBlockCount": block_type_counts["LIST"],
            "tableBlockCount": block_type_counts["TABLE"],
            "codeBlockCount": block_type_counts["CODE"],
            "captionBlockCount": block_type_counts["CAPTION"],
            "structuredBlockRatio": round(structured_count / max(1, len(content_blocks)), 4),
            "headingPathCoverage": round(heading_path_count / max(1, len(content_blocks)), 4),
            "featureGateListBlocks": len(feature_gate_blocks),
            "featureGateListItems": feature_gate_items,
            "sourceMarkupLeakageBlocks": len(pdf_markup_blocks),
        },
    )


def _pdf_markup_leakage_blocks(
    blocks: list[NormalizedBlock],
    media_type: str,
) -> list[NormalizedBlock]:
    if media_type != "application/pdf":
        return []
    marker = re.compile(
        r"(?m)(?:^\s*:{3,}(?:[A-Za-z]+[^\n]*)?$|"
        r"\*\*(?:注意|提示|警告|说明|实验功能)\*\*\s*[:：]?|"
        r"^\s*>\s*[-*+]\s+)"
    )
    return [block for block in blocks if marker.search(block.text)]


def _utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def _source_name(request: ParseDocumentRequest, downloaded: DownloadedObject) -> str:
    candidates = [
        request.file_name,
        _content_disposition_filename(downloaded.content_disposition),
        unquote(Path(urlsplit(request.source_url).path).name),
        "document",
    ]
    for candidate in candidates:
        if candidate and candidate.strip():
            normalized = candidate.replace("\\", "/").rsplit("/", 1)[-1].strip()
            if normalized:
                return normalized
    return "document"


def _content_disposition_filename(value: str | None) -> str | None:
    if not value:
        return None
    message = Message()
    message["content-disposition"] = value
    return message.get_filename()


def pretty_contract(document: NormalizedDocument) -> str:
    return json.dumps(document.model_dump(mode="json", by_alias=True), ensure_ascii=False, indent=2)
