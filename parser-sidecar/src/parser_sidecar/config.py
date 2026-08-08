from __future__ import annotations

import os
from dataclasses import dataclass


def _positive_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def _positive_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        value = float(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number") from exc
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


@dataclass(frozen=True, slots=True)
class Settings:
    host: str = "0.0.0.0"
    port: int = 8090
    log_level: str = "INFO"
    http_timeout_seconds: float = 60.0
    max_download_bytes: int = 64 * 1024 * 1024
    max_archive_bytes: int = 256 * 1024 * 1024

    @classmethod
    def from_environment(cls) -> Settings:
        return cls(
            host=os.getenv("PARSER_HOST", "0.0.0.0"),
            port=_positive_int("PARSER_PORT", 8090),
            log_level=os.getenv("PARSER_LOG_LEVEL", "INFO").upper(),
            http_timeout_seconds=_positive_float("PARSER_HTTP_TIMEOUT_SECONDS", 60.0),
            max_download_bytes=_positive_int(
                "PARSER_MAX_DOWNLOAD_BYTES",
                64 * 1024 * 1024,
            ),
            max_archive_bytes=_positive_int(
                "PARSER_MAX_ARCHIVE_BYTES",
                256 * 1024 * 1024,
            ),
        )

