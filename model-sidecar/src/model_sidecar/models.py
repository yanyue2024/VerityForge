from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field, field_validator


class EmbeddingRequest(BaseModel):
    model: str | None = None
    input: str | list[str]
    encoding_format: str | None = None
    dimensions: int | None = None

    @field_validator("input")
    @classmethod
    def input_must_not_be_blank(cls, value: str | list[str]) -> str | list[str]:
        values = [value] if isinstance(value, str) else value
        if not values or any(not item.strip() for item in values):
            raise ValueError("input must contain non-blank text")
        return value


class RerankRequest(BaseModel):
    model: str | None = None
    query: str = Field(min_length=1)
    documents: list[str]
    top_n: int | None = Field(default=None, ge=1)
    additional_data: dict[str, Any] | None = None

    @field_validator("documents")
    @classmethod
    def documents_must_not_be_blank(cls, value: list[str]) -> list[str]:
        if not value or any(not item.strip() for item in value):
            raise ValueError("documents must contain non-blank text")
        return value
