#!/usr/bin/env python3
"""Upload the Chinese enterprise corpus and import its evaluation blueprint."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import threading
import time
from typing import Any
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
DATASET_ID = "chinese-enterprise-rag-v1"
DEFAULT_DATASET = ROOT / "data" / DATASET_ID
DEFAULT_BLUEPRINT = ROOT / "benchmarks" / f"{DATASET_ID}.blueprint.json"
DEFAULT_KNOWLEDGE_BASE = "中文企业技术知识库 v1"
TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED"}
print_lock = threading.Lock()


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key or key in os.environ:
            continue
        try:
            parsed = shlex.split(value.strip(), comments=True)
        except ValueError:
            continue
        os.environ[key] = parsed[0] if parsed else ""


class ApiClient:
    def __init__(self, base_url: str, token: str | None = None) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token

    def request(
        self,
        method: str,
        path_or_url: str,
        *,
        json_body: Any | None = None,
        data: bytes | None = None,
        headers: dict[str, str] | None = None,
        expected: set[int] | None = None,
    ) -> tuple[int, Any | None]:
        url = path_or_url if path_or_url.startswith(("http://", "https://")) else self.base_url + path_or_url
        request_headers = dict(headers or {})
        if self.token and not path_or_url.startswith(("http://", "https://")):
            request_headers["Authorization"] = f"Bearer {self.token}"
        if json_body is not None:
            data = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                status = response.status
                payload = response.read()
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {url} returned HTTP {exc.code}: {detail}") from exc
        allowed = expected or {200, 201, 202, 204}
        if status not in allowed:
            raise RuntimeError(f"{method} {url} returned unexpected HTTP {status}")
        if not payload:
            return status, None
        content_type = response.headers.get("Content-Type", "")
        return status, json.loads(payload) if "json" in content_type else payload


def login(api_url: str, username: str, password: str) -> ApiClient:
    client = ApiClient(api_url)
    _status, result = client.request(
        "POST", "/api/v1/auth/login", json_body={"username": username, "password": password}
    )
    if not isinstance(result, dict) or not result.get("accessToken"):
        raise RuntimeError("Login response did not include accessToken")
    return ApiClient(api_url, str(result["accessToken"]))


def require_knowledge_base(client: ApiClient, name: str) -> str:
    _status, result = client.request("GET", "/api/v1/knowledge-bases")
    matches = [item for item in result if item.get("name") == name]
    if len(matches) > 1:
        raise RuntimeError(f"More than one knowledge base is named {name!r}")
    if matches:
        return str(matches[0]["id"])
    _status, created = client.request(
        "POST",
        "/api/v1/knowledge-bases",
        json_body={
            "name": name,
            "description": "200篇公开许可中文企业技术文档，覆盖PDF、DOCX、HTML和Markdown。",
        },
    )
    return str(created["id"])


def ensure_metadata_schema(client: ApiClient, knowledge_base_id: str) -> None:
    status, schema = client.request(
        "GET", f"/api/v1/knowledge-bases/{knowledge_base_id}/metadata-schema", expected={200, 204}
    )
    required = {
        "dataset_id": "数据集",
        "document_alias": "文档别名",
        "source_project": "公开来源",
        "business_domain": "知识领域",
        "source_format": "源格式",
        "license": "许可证",
        "upstream_commit": "上游提交",
    }
    if status == 200:
        present = {field["key"] for field in schema.get("fields", [])}
        missing = sorted(required.keys() - present)
        if missing:
            raise RuntimeError(
                "The existing metadata schema is missing dataset fields: " + ", ".join(missing)
            )
        return
    fields = [
        {
            "key": key,
            "label": label,
            "type": "TEXT",
            "required": False,
            "filterable": True,
            "allowedValues": [],
        }
        for key, label in required.items()
    ]
    client.request(
        "PUT", f"/api/v1/knowledge-bases/{knowledge_base_id}/metadata-schema",
        json_body={"fields": fields},
    )


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def upload_one(
    client: ApiClient,
    knowledge_base_id: str,
    dataset_root: Path,
    entry: dict[str, Any],
) -> str:
    path = dataset_root / entry["relativePath"]
    if file_sha256(path) != entry["sha256"]:
        raise RuntimeError(f"Local checksum mismatch: {path}")
    metadata = {
        "dataset_id": DATASET_ID,
        "document_alias": entry["alias"],
        "source_project": entry["sourceProject"],
        "business_domain": entry["domain"],
        "source_format": entry["format"],
        "license": entry["license"],
        "upstream_commit": entry["upstreamCommit"],
    }
    _status, intent = client.request(
        "POST",
        f"/api/v1/knowledge-bases/{knowledge_base_id}/documents/upload-intents",
        json_body={
            "title": entry["title"],
            "fileName": entry["fileName"],
            "contentType": entry["mimeType"],
            "byteSize": path.stat().st_size,
            "sha256": entry["sha256"],
            "metadata": metadata,
        },
    )
    upload_headers = {str(key): str(value) for key, value in intent.get("headers", {}).items()}
    client.request(
        str(intent.get("method", "PUT")), str(intent["uploadUrl"]),
        data=path.read_bytes(), headers=upload_headers, expected={200, 201, 204},
    )
    _status, completed = client.request("POST", f"/api/v1/uploads/{intent['uploadId']}/complete")
    with print_lock:
        print(f"Submitted {entry['alias']} ({entry['format']})", flush=True)
    return str(completed["jobId"])


def existing_documents(client: ApiClient, knowledge_base_id: str) -> dict[str, dict[str, Any]]:
    _status, values = client.request("GET", f"/api/v1/knowledge-bases/{knowledge_base_id}/documents")
    return {str(value["title"]): value for value in values}


def existing_job(client: ApiClient, document: dict[str, Any]) -> str | None:
    _status, detail = client.request("GET", f"/api/v1/documents/{document['id']}")
    versions = detail.get("versions", [])
    return str(versions[0]["ingestionJobId"]) if versions and versions[0].get("ingestionJobId") else None


def wait_for_jobs(client: ApiClient, jobs: dict[str, str], deadline_seconds: int) -> None:
    deadline = time.monotonic() + deadline_seconds
    pending = dict(jobs)
    failures: list[str] = []
    while pending:
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out waiting for {len(pending)} ingestion jobs")
        for alias, job_id in list(pending.items()):
            _status, job = client.request("GET", f"/api/v1/ingestion-jobs/{job_id}")
            status = str(job["status"])
            if status not in TERMINAL_STATUSES:
                continue
            pending.pop(alias)
            if status != "SUCCEEDED":
                failures.append(f"{alias}:{status}:{job.get('errorMessage') or ''}")
        if pending:
            print(f"Waiting for {len(pending)} ingestion jobs", flush=True)
            time.sleep(2.5)
    if failures:
        raise RuntimeError("Ingestion failed: " + "; ".join(failures))


def import_evaluation(args: argparse.Namespace) -> None:
    command = [
        str(ROOT / "scripts" / "import-evaluation-blueprint.sh"),
        "--blueprint", str(args.blueprint),
        "--output", str(ROOT / "tmp" / "benchmarks" / f"{DATASET_ID}.json"),
    ]
    subprocess.run(command, cwd=ROOT, check=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--blueprint", type=Path, default=DEFAULT_BLUEPRINT)
    parser.add_argument("--api-url", default=os.getenv("API_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--knowledge-base-name", default=DEFAULT_KNOWLEDGE_BASE)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--skip-evaluation", action="store_true")
    return parser.parse_args()


def main() -> None:
    load_dotenv(ROOT / ".env")
    args = parse_args()
    if not 1 <= args.concurrency <= 16:
        raise RuntimeError("--concurrency must be between 1 and 16")
    manifest_path = args.dataset / "metadata" / "manifest.jsonl"
    if not manifest_path.is_file():
        raise RuntimeError(f"Dataset manifest not found: {manifest_path}")
    manifest = [json.loads(line) for line in manifest_path.read_text(encoding="utf-8").splitlines() if line]
    if len(manifest) != 200:
        raise RuntimeError(f"Expected 200 manifest entries, found {len(manifest)}")
    username = os.getenv("RAG_USERNAME") or os.getenv("RAG_BOOTSTRAP_ADMIN_USERNAME") or "admin"
    password = (
        os.getenv("RAG_PASSWORD") or os.getenv("ADMIN_PASSWORD")
        or os.getenv("RAG_BOOTSTRAP_ADMIN_PASSWORD")
    )
    if not password:
        raise RuntimeError("Set RAG_PASSWORD or RAG_BOOTSTRAP_ADMIN_PASSWORD")
    client = login(args.api_url, username, password)
    knowledge_base_id = require_knowledge_base(client, args.knowledge_base_name)
    ensure_metadata_schema(client, knowledge_base_id)
    documents = existing_documents(client, knowledge_base_id)
    jobs: dict[str, str] = {}
    pending_entries: list[dict[str, Any]] = []
    skipped = 0
    for entry in manifest:
        existing = documents.get(entry["title"])
        if existing and existing.get("status") == "ACTIVE":
            skipped += 1
            continue
        if existing:
            job_id = existing_job(client, existing)
            if job_id:
                jobs[entry["alias"]] = job_id
                continue
            raise RuntimeError(f"Existing non-active document has no ingestion job: {entry['title']}")
        pending_entries.append(entry)
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = {
            executor.submit(upload_one, client, knowledge_base_id, args.dataset, entry): entry
            for entry in pending_entries
        }
        for future in as_completed(futures):
            entry = futures[future]
            jobs[entry["alias"]] = future.result()
    wait_for_jobs(client, jobs, args.timeout_seconds)
    active = existing_documents(client, knowledge_base_id)
    missing = [entry["title"] for entry in manifest if active.get(entry["title"], {}).get("status") != "ACTIVE"]
    if missing:
        raise RuntimeError(f"Documents were not ACTIVE after ingestion: {missing[:10]}")
    print(f"Corpus ready: uploaded={len(pending_entries)}, skipped={skipped}, active=200")
    if not args.skip_evaluation:
        import_evaluation(args)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
