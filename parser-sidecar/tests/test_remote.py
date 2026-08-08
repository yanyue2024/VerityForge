from __future__ import annotations

import httpx
import pytest

from parser_sidecar.errors import PayloadTooLargeError
from parser_sidecar.remote import RemoteObjectClient


@pytest.mark.asyncio
async def test_remote_client_preserves_presigned_urls_and_puts_json() -> None:
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.method == "GET":
            return httpx.Response(
                200,
                content=b"hello",
                headers={
                    "content-type": "text/plain",
                    "content-disposition": 'attachment; filename="hello.txt"',
                },
            )
        assert await request.aread() == b'{"ok":true}'
        return httpx.Response(200)

    client = RemoteObjectClient(
        timeout_seconds=5,
        max_download_bytes=1024,
        transport=httpx.MockTransport(handler),
    )
    source_url = "https://store.example/source?X-Amz-Signature=a%2Fb"
    result_url = "https://store.example/result?X-Amz-Signature=c%2Fd"

    downloaded = await client.download(source_url)
    await client.put_json(result_url, b'{"ok":true}')

    assert downloaded.content == b"hello"
    assert str(requests[0].url) == source_url
    assert str(requests[1].url) == result_url
    assert requests[1].headers["content-type"] == "application/json"


@pytest.mark.asyncio
async def test_remote_client_enforces_streaming_size_limit() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"too large")

    client = RemoteObjectClient(
        timeout_seconds=5,
        max_download_bytes=4,
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(PayloadTooLargeError):
        await client.download("https://store.example/source")

