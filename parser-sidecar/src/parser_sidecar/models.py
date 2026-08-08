from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, field_validator


class ContractModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class BlockType(StrEnum):
    TITLE = "TITLE"
    HEADING = "HEADING"
    PARAGRAPH = "PARAGRAPH"
    LIST = "LIST"
    TABLE = "TABLE"
    IMAGE = "IMAGE"
    CAPTION = "CAPTION"
    CODE = "CODE"
    PAGE_HEADER = "PAGE_HEADER"
    PAGE_FOOTER = "PAGE_FOOTER"


class ParseQualityStatus(StrEnum):
    PASS = "PASS"
    WARNING = "WARNING"
    FAIL = "FAIL"


class BoundingBox(ContractModel):
    x: float
    y: float
    width: float
    height: float


class ParseDocumentRequest(ContractModel):
    source_url: str = Field(alias="sourceUrl")
    result_url: str | None = Field(default=None, alias="resultUrl")
    file_name: str | None = Field(default=None, alias="fileName")
    content_type: str | None = Field(default=None, alias="contentType")
    parser_profile: str = Field(default="AUTO", alias="parserProfile")
    options: dict[str, Any] = Field(default_factory=dict)

    @field_validator("source_url", "result_url")
    @classmethod
    def validate_http_url(cls, value: str | None) -> str | None:
        if value is None:
            return None
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("must be an absolute HTTP(S) URL")
        return value

    @field_validator("parser_profile", mode="before")
    @classmethod
    def normalize_profile(cls, value: object) -> str:
        if value is None:
            return "AUTO"
        profile = str(value).strip().upper()
        return profile or "AUTO"

    @field_validator("options", mode="before")
    @classmethod
    def normalize_options(cls, value: object) -> object:
        return {} if value is None else value


class NormalizedBlock(ContractModel):
    block_id: str = Field(alias="blockId")
    type: BlockType
    text: str
    order_index: int = Field(alias="orderIndex")
    page_number: int | None = Field(default=None, alias="pageNumber")
    heading_path: list[str] = Field(default_factory=list, alias="headingPath")
    bounding_box: BoundingBox | None = Field(default=None, alias="boundingBox")
    source_start: int | None = Field(default=None, alias="sourceStart")
    source_end: int | None = Field(default=None, alias="sourceEnd")
    source_offset_unit: str = Field(default="UTF16_CODE_UNIT", alias="sourceOffsetUnit")
    attributes: dict[str, Any] = Field(default_factory=dict)


class ParseQualityIssue(ContractModel):
    code: str
    severity: ParseQualityStatus
    message: str
    block_ids: list[str] = Field(default_factory=list, alias="blockIds")


class ParseQualityReport(ContractModel):
    status: ParseQualityStatus
    score: int = Field(ge=0, le=100)
    issues: list[ParseQualityIssue] = Field(default_factory=list)
    metrics: dict[str, Any] = Field(default_factory=dict)


class NormalizedDocument(ContractModel):
    schema_version: str = Field(default="2.0", alias="schemaVersion")
    parser_name: str = Field(alias="parserName")
    parser_version: str = Field(alias="parserVersion")
    title: str
    source_name: str = Field(alias="sourceName")
    content_hash: str = Field(alias="contentHash")
    parsed_at: datetime = Field(alias="parsedAt")
    metadata: dict[str, Any] = Field(default_factory=dict)
    normalized_markdown: str = Field(default="", alias="normalizedMarkdown")
    quality: ParseQualityReport
    blocks: list[NormalizedBlock] = Field(default_factory=list)


class HealthResponse(ContractModel):
    status: str
    parser_name: str = Field(alias="parserName")
    parser_version: str = Field(alias="parserVersion")
    schema_version: str = Field(alias="schemaVersion")
    capabilities: dict[str, Any]
