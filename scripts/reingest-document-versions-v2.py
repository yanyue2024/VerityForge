#!/usr/bin/env python3
"""Reingest published documents as V2 versions through the public upload API."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import time
from typing import Any
import urllib.error
import urllib.request


TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED", "AWAITING_REVIEW"}
OPTIONAL_SYSTEM_FIELDS = {"organization", "department", "category", "valid_to"}
CORPUS_FIELDS = [
    {"key": "dataset_id", "label": "数据集", "type": "TEXT"},
    {"key": "document_alias", "label": "文档别名", "type": "TEXT"},
    {"key": "source_format", "label": "源格式", "type": "TEXT"},
    {"key": "business_domain", "label": "知识领域", "type": "TEXT"},
    {"key": "upstream_commit", "label": "上游版本", "type": "TEXT"},
]
FORMAT_MIME = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "html": "text/html",
    "md": "text/markdown",
}


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
        timeout: int = 300,
        expected: set[int] | None = None,
    ) -> tuple[int, Any | None]:
        external = path_or_url.startswith(("http://", "https://"))
        url = path_or_url if external else self.base_url + path_or_url
        request_headers = dict(headers or {})
        if self.token and not external:
            request_headers["Authorization"] = f"Bearer {self.token}"
        if json_body is not None:
            data = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                status = response.status
                payload = response.read()
                content_type = response.headers.get("Content-Type", "")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {url} returned HTTP {exc.code}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"{method} {url} failed: {exc.reason}") from exc
        allowed = expected or {200, 201, 202, 204}
        if status not in allowed:
            raise RuntimeError(f"{method} {url} returned unexpected HTTP {status}")
        if not payload:
            return status, None
        return status, json.loads(payload) if "json" in content_type else payload


def login(api_url: str) -> ApiClient:
    access_token = os.getenv("RAG_ACCESS_TOKEN")
    if access_token:
        return ApiClient(api_url, access_token)
    username = os.getenv("RAG_USERNAME") or os.getenv("RAG_BOOTSTRAP_ADMIN_USERNAME") or "admin"
    password = (
        os.getenv("RAG_PASSWORD")
        or os.getenv("ADMIN_PASSWORD")
        or os.getenv("RAG_BOOTSTRAP_ADMIN_PASSWORD")
    )
    if not password:
        raise RuntimeError("Set RAG_PASSWORD or RAG_BOOTSTRAP_ADMIN_PASSWORD")
    client = ApiClient(api_url)
    _status, result = client.request(
        "POST", "/api/v1/auth/login", json_body={"username": username, "password": password}
    )
    if not isinstance(result, dict) or not result.get("accessToken"):
        raise RuntimeError("Login response did not include accessToken")
    return ApiClient(api_url, str(result["accessToken"]))


def metadata(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str) and value.strip():
        parsed = json.loads(value)
        return parsed if isinstance(parsed, dict) else {}
    return {}


def latest_version(detail: dict[str, Any]) -> dict[str, Any] | None:
    versions = detail.get("versions") or []
    return versions[0] if versions else None


def is_v2(version: dict[str, Any] | None) -> bool:
    if not version:
        return False
    schema = str(version.get("parserSchemaVersion") or "")
    return schema == "2" or bool(version.get("parseQualityStatus"))


def ensure_migration_schema(client: ApiClient) -> None:
    status, schema = client.request("GET", "/api/v1/metadata-schema", expected={200, 204})
    if status == 204 or not isinstance(schema, dict):
        raise RuntimeError("An active organization Metadata schema is required for V2 migration")
    fields = [dict(value) for value in schema.get("fields", [])]
    changed = False
    for field in fields:
        if field.get("key") in OPTIONAL_SYSTEM_FIELDS and field.get("required"):
            field["required"] = False
            changed = True
    present = {str(field.get("key")) for field in fields}
    for definition in CORPUS_FIELDS:
        if definition["key"] in present:
            continue
        fields.append({
            **definition,
            "required": False,
            "filterable": True,
            "allowedValues": [],
        })
        changed = True
    if changed:
        client.request("PUT", "/api/v1/metadata-schema", json_body={"fields": fields})
        print("Activated a migration-compatible organization Metadata schema", flush=True)


def upload_version(
    client: ApiClient,
    knowledge_base_id: str,
    document: dict[str, Any],
    dataset_root: Path | None,
) -> str:
    _status, detail = client.request("GET", f"/api/v1/documents/{document['id']}")
    current_id = str(detail.get("currentVersionId") or "")
    versions = detail.get("versions") or []
    source = next((value for value in versions if str(value.get("id")) == current_id), None)
    source = source or next((value for value in versions if value.get("status") == "PUBLISHED"), None)
    if not source:
        raise RuntimeError(f"Document {document['id']} has no published source version")

    _status, asset = client.request("GET", f"/api/v1/document-versions/{source['id']}/asset")
    if dataset_root is None:
        _status, payload = client.request("GET", str(asset["previewUrl"]), timeout=300)
        if not isinstance(payload, bytes):
            raise RuntimeError(f"Asset for {document['id']} was not binary")
        digest = hashlib.sha256(payload).hexdigest()
        expected_hash = str(asset.get("fileHash") or "").lower()
        if expected_hash and digest != expected_hash:
            raise RuntimeError(f"Asset checksum mismatch for document {document['id']}")
        file_name = str(asset["fileName"])
        content_type = str(asset["contentType"])
    else:
        source_metadata = metadata(source.get("metadata"))
        alias = str(source_metadata.get("document_alias") or Path(str(asset["fileName"])).stem)
        source_format = str(source_metadata.get("source_format") or Path(str(asset["fileName"])).suffix[1:]).lower()
        if source_format not in FORMAT_MIME:
            raise RuntimeError(f"Unsupported dataset source format for {document['id']}: {source_format}")
        replacement = dataset_root / "corpus" / source_format / f"{alias}.{source_format}"
        if not replacement.is_file():
            raise RuntimeError(f"Replacement dataset asset is missing: {replacement}")
        payload = replacement.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        file_name = replacement.name
        content_type = FORMAT_MIME[source_format]

    body = {
        "title": detail["title"],
        "fileName": file_name,
        "contentType": content_type,
        "byteSize": len(payload),
        "sha256": digest,
        "metadata": metadata(source.get("metadata")),
        "validFrom": source.get("validFrom"),
        "validTo": source.get("validTo"),
        "documentId": detail["id"],
    }
    _status, intent = client.request(
        "POST",
        f"/api/v1/knowledge-bases/{knowledge_base_id}/documents/upload-intents",
        json_body=body,
    )
    upload_headers = {str(key): str(value) for key, value in (intent.get("headers") or {}).items()}
    client.request(
        str(intent.get("method") or "PUT"),
        str(intent["uploadUrl"]),
        data=payload,
        headers=upload_headers,
        expected={200, 201, 204},
    )
    _status, completed = client.request("POST", f"/api/v1/uploads/{intent['uploadId']}/complete")
    return str(completed["jobId"])


def wait_for_jobs(client: ApiClient, jobs: dict[str, str], timeout_seconds: int) -> dict[str, list[str]]:
    deadline = time.monotonic() + timeout_seconds
    pending = dict(jobs)
    outcomes: dict[str, list[str]] = {status: [] for status in TERMINAL_STATUSES}
    while pending:
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out waiting for {len(pending)} V2 ingestion jobs")
        for title, job_id in list(pending.items()):
            _status, job = client.request("GET", f"/api/v1/ingestion-jobs/{job_id}")
            status = str(job["status"])
            if status not in TERMINAL_STATUSES:
                continue
            outcomes[status].append(title)
            pending.pop(title)
        if pending:
            print(f"Waiting for {len(pending)} V2 ingestion jobs", flush=True)
            time.sleep(3)
    return outcomes


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-url", default=os.getenv("API_URL", "http://127.0.0.1:18084"))
    parser.add_argument("--knowledge-base-name")
    parser.add_argument("--document-title", help="only reingest documents with this exact title")
    parser.add_argument(
        "--source-type",
        choices=("DOCX", "HTML", "MD", "PDF", "TXT", "XLSX"),
        help="only reingest documents whose current version has this source type",
    )
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--timeout-seconds", type=int, default=7200)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--skip-schema-merge", action="store_true")
    parser.add_argument("--force", action="store_true", help="create another V2 generation even when one exists")
    parser.add_argument(
        "--latest-ingestion-status",
        choices=tuple(sorted(TERMINAL_STATUSES | {"PENDING", "RUNNING"})),
        help="only reingest documents whose newest version has this ingestion status",
    )
    parser.add_argument(
        "--dataset-root",
        type=Path,
        help="upload regenerated corpus files from this dataset instead of reusing stored assets",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not 1 <= args.concurrency <= 8:
        raise RuntimeError("--concurrency must be between 1 and 8")
    client = login(args.api_url)
    if not args.skip_schema_merge:
        ensure_migration_schema(client)
    _status, knowledge_bases = client.request("GET", "/api/v1/knowledge-bases")
    if args.knowledge_base_name:
        knowledge_bases = [kb for kb in knowledge_bases if kb.get("name") == args.knowledge_base_name]
        if len(knowledge_bases) != 1:
            raise RuntimeError(f"Knowledge base not found: {args.knowledge_base_name}")

    candidates: list[tuple[str, dict[str, Any]]] = []
    tracked_jobs: dict[str, str] = {}
    skipped = 0
    for knowledge_base in knowledge_bases:
        knowledge_base_id = str(knowledge_base["id"])
        _status, documents = client.request(
            "GET", f"/api/v1/knowledge-bases/{knowledge_base_id}/documents"
        )
        for document in documents:
            if args.document_title and document.get("title") != args.document_title:
                continue
            _status, detail = client.request("GET", f"/api/v1/documents/{document['id']}")
            latest = latest_version(detail)
            if args.latest_ingestion_status and str((latest or {}).get("ingestionStatus") or "") != args.latest_ingestion_status:
                continue
            if args.source_type and str((latest or {}).get("sourceType") or "").upper() != args.source_type:
                continue
            if is_v2(latest) and not args.force:
                skipped += 1
                status = str(latest.get("ingestionStatus") or "")
                job_id = latest.get("ingestionJobId")
                if job_id and status not in TERMINAL_STATUSES:
                    tracked_jobs[str(document["title"])] = str(job_id)
                continue
            if args.force and latest:
                status = str(latest.get("ingestionStatus") or "")
                job_id = latest.get("ingestionJobId")
                if job_id and status not in TERMINAL_STATUSES:
                    tracked_jobs[str(document["title"])] = str(job_id)
                    continue
            candidates.append((knowledge_base_id, document))
    if args.limit is not None:
        candidates = candidates[: args.limit]

    submitted: dict[str, str] = {}
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = {
            executor.submit(upload_version, client, knowledge_base_id, document, args.dataset_root): document
            for knowledge_base_id, document in candidates
        }
        for future in as_completed(futures):
            document = futures[future]
            title = str(document["title"])
            submitted[title] = future.result()
            print(f"Submitted V2 version: {title}", flush=True)

    outcomes = wait_for_jobs(client, {**tracked_jobs, **submitted}, args.timeout_seconds)
    print(
        "V2 migration finished: "
        f"submitted={len(submitted)}, skipped={skipped}, succeeded={len(outcomes['SUCCEEDED'])}, "
        f"awaiting_review={len(outcomes['AWAITING_REVIEW'])}, failed={len(outcomes['FAILED'])}, "
        f"cancelled={len(outcomes['CANCELLED'])}",
        flush=True,
    )
    if outcomes["AWAITING_REVIEW"]:
        print("Quality review required: " + "; ".join(outcomes["AWAITING_REVIEW"]), flush=True)
    if outcomes["FAILED"] or outcomes["CANCELLED"]:
        raise RuntimeError("One or more V2 versions did not complete successfully")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"error: {exc}", file=os.sys.stderr)
        raise SystemExit(1) from exc
