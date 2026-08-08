from __future__ import annotations

from dataclasses import dataclass

import httpx

from parser_sidecar.errors import DownloadError, PayloadTooLargeError, UploadError


@dataclass(frozen=True, slots=True)
class DownloadedObject:
    content: bytes
    content_type: str | None = None
    content_disposition: str | None = None


class RemoteObjectClient:
    def __init__(
        self,
        *,
        timeout_seconds: float,
        max_download_bytes: int,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._timeout = httpx.Timeout(timeout_seconds)
        self._max_download_bytes = max_download_bytes
        self._transport = transport

    async def download(self, url: str) -> DownloadedObject:
        try:
            async with httpx.AsyncClient(
                timeout=self._timeout,
                follow_redirects=True,
                transport=self._transport,
            ) as client:
                async with client.stream("GET", url, headers={"Accept": "*/*"}) as response:
                    response.raise_for_status()
                    self._check_content_length(response.headers.get("content-length"))
                    content = bytearray()
                    async for chunk in response.aiter_bytes():
                        content.extend(chunk)
                        if len(content) > self._max_download_bytes:
                            raise PayloadTooLargeError(
                                f"Downloaded document exceeds {self._max_download_bytes} bytes"
                            )
                    return DownloadedObject(
                        content=bytes(content),
                        content_type=response.headers.get("content-type"),
                        content_disposition=response.headers.get("content-disposition"),
                    )
        except PayloadTooLargeError:
            raise
        except httpx.HTTPStatusError as exc:
            raise DownloadError(
                f"Source download failed with HTTP {exc.response.status_code}"
            ) from exc
        except httpx.HTTPError as exc:
            raise DownloadError("Source download failed") from exc

    async def put_json(self, url: str, payload: bytes) -> None:
        try:
            async with httpx.AsyncClient(
                timeout=self._timeout,
                follow_redirects=True,
                transport=self._transport,
            ) as client:
                response = await client.put(
                    url,
                    content=payload,
                    headers={"Content-Type": "application/json"},
                )
                response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            raise UploadError(
                f"Result upload failed with HTTP {exc.response.status_code}"
            ) from exc
        except httpx.HTTPError as exc:
            raise UploadError("Result upload failed") from exc

    def _check_content_length(self, raw_value: str | None) -> None:
        if raw_value is None:
            return
        try:
            content_length = int(raw_value)
        except ValueError:
            return
        if content_length > self._max_download_bytes:
            raise PayloadTooLargeError(
                f"Downloaded document exceeds {self._max_download_bytes} bytes"
            )

