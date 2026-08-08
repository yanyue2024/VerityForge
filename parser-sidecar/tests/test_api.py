from __future__ import annotations

import json
from importlib.metadata import version

import httpx
import pytest

from parser_sidecar import __version__
from parser_sidecar.config import Settings
from parser_sidecar.main import create_app
from parser_sidecar.remote import DownloadedObject


def test_runtime_version_matches_package_metadata() -> None:
    assert __version__ == version("rag-parser-sidecar")


class FakeRemoteClient:
    def __init__(self) -> None:
        self.download_url: str | None = None
        self.upload_url: str | None = None
        self.upload_payload: bytes | None = None

    async def download(self, url: str) -> DownloadedObject:
        self.download_url = url
        return DownloadedObject(
            content=b"First paragraph.\n\nSecond paragraph.",
            content_type="text/plain; charset=utf-8",
            content_disposition='attachment; filename="remote-name.txt"',
        )

    async def put_json(self, url: str, payload: bytes) -> None:
        self.upload_url = url
        self.upload_payload = payload


@pytest.mark.asyncio
async def test_health_reports_optional_profile() -> None:
    app = create_app(Settings(), FakeRemoteClient())

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
    ) as client:
        response = await client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert response.json()["schemaVersion"] == "2.0"
    assert "doclingAvailable" in response.json()["capabilities"]
    assert "HTML" in response.json()["capabilities"]["formats"]


@pytest.mark.asyncio
async def test_parse_returns_and_uploads_the_same_contract() -> None:
    remote = FakeRemoteClient()
    app = create_app(Settings(), remote)
    source_url = "https://objects.example/source?X-Amz-Signature=source-secret"
    result_url = "https://objects.example/result?X-Amz-Signature=result-secret"

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
    ) as client:
        response = await client.post(
            "/v1/parse",
            json={
                "sourceUrl": source_url,
                "resultUrl": result_url,
                "parserProfile": None,
                "options": None,
            },
        )

    assert response.status_code == 200
    result = response.json()
    assert remote.download_url == source_url
    assert remote.upload_url == result_url
    assert json.loads(remote.upload_payload or b"{}") == result
    assert result["sourceName"] == "remote-name.txt"
    assert result["parserName"] == "parser-sidecar"
    assert result["schemaVersion"] == "2.0"
    assert result["metadata"]["parserOptions"] == {}
    assert result["normalizedMarkdown"]
    assert result["quality"]["status"] == "PASS"
    assert [block["orderIndex"] for block in result["blocks"]] == [0, 1]
    assert all(block["blockId"].startswith("blk_v2_") for block in result["blocks"])

    expected_document_keys = {
        "schemaVersion",
        "parserName",
        "parserVersion",
        "title",
        "sourceName",
        "contentHash",
        "parsedAt",
        "metadata",
        "normalizedMarkdown",
        "quality",
        "blocks",
    }
    expected_block_keys = {
        "blockId",
        "type",
        "text",
        "orderIndex",
        "pageNumber",
        "headingPath",
        "boundingBox",
        "sourceStart",
        "sourceEnd",
        "sourceOffsetUnit",
        "attributes",
    }
    assert set(result) == expected_document_keys
    assert set(result["blocks"][0]) == expected_block_keys


@pytest.mark.asyncio
async def test_parse_rejects_non_http_source_url() -> None:
    app = create_app(Settings(), FakeRemoteClient())

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
    ) as client:
        response = await client.post(
            "/v1/parse",
            json={"sourceUrl": "file:///etc/passwd"},
        )

    assert response.status_code == 422


@pytest.mark.asyncio
async def test_parse_reports_unsupported_media_type() -> None:
    class BinaryRemote(FakeRemoteClient):
        async def download(self, url: str) -> DownloadedObject:
            return DownloadedObject(
                content=b"\x00\x01\x02",
                content_type="application/octet-stream",
            )

    app = create_app(Settings(), BinaryRemote())

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
    ) as client:
        response = await client.post(
            "/v1/parse",
            json={"sourceUrl": "https://objects.example/blob", "fileName": "blob.bin"},
        )

    assert response.status_code == 415
    assert response.json()["detail"]["code"] == "unsupported_document"
