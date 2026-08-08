from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response

from parser_sidecar import __version__
from parser_sidecar.config import Settings
from parser_sidecar.errors import ParserSidecarError
from parser_sidecar.models import HealthResponse, NormalizedDocument, ParseDocumentRequest
from parser_sidecar.parsers import docling_available
from parser_sidecar.remote import RemoteObjectClient
from parser_sidecar.service import SCHEMA_VERSION, ParserService


def create_app(
    settings: Settings | None = None,
    remote_client: Any | None = None,
) -> FastAPI:
    resolved_settings = settings or Settings.from_environment()
    logging.basicConfig(
        level=resolved_settings.log_level,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    app = FastAPI(
        title="RAG Parser Sidecar",
        version=__version__,
        docs_url="/docs",
        redoc_url=None,
    )
    app.state.settings = resolved_settings
    app.state.remote_client = remote_client or RemoteObjectClient(
        timeout_seconds=resolved_settings.http_timeout_seconds,
        max_download_bytes=resolved_settings.max_download_bytes,
    )
    app.state.parser_service = ParserService(
        resolved_settings,
        app.state.remote_client,
    )

    @app.exception_handler(ParserSidecarError)
    async def parser_error_handler(
        _request: Request,
        exception: ParserSidecarError,
    ) -> JSONResponse:
        return JSONResponse(
            status_code=exception.status_code,
            content={
                "detail": {
                    "code": exception.code,
                    "message": str(exception),
                }
            },
        )

    @app.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(
            status="ok",
            parser_name="parser-sidecar",
            parser_version=__version__,
            schema_version=SCHEMA_VERSION,
            capabilities={
                "formats": ["PDF", "DOCX", "XLSX", "HTML", "MARKDOWN", "TEXT"],
                "profiles": ["AUTO", "LIGHTWEIGHT", "DOCLING"],
                "doclingAvailable": docling_available(),
            },
        )

    @app.post("/v1/parse", response_model=NormalizedDocument)
    async def parse(request: ParseDocumentRequest) -> Response:
        _document, payload = await app.state.parser_service.parse(request)
        return Response(content=payload, media_type="application/json")

    return app


app = create_app()
