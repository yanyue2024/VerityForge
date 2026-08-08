from __future__ import annotations

import os
from dataclasses import dataclass


def _boolean(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True, slots=True)
class Settings:
    embed_model_path: str
    rerank_model_path: str
    embed_model_name: str
    rerank_model_name: str
    embed_dimension: int
    embed_max_length: int
    rerank_max_length: int
    embedding_batch_size: int
    rerank_batch_size: int
    max_inputs: int
    max_documents: int
    device: str
    preload: bool

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(
            embed_model_path=os.getenv("EMBED_MODEL_PATH", "/models/embedding"),
            rerank_model_path=os.getenv("RERANK_MODEL_PATH", "/models/reranker"),
            embed_model_name=os.getenv("EMBED_MODEL_NAME", "BAAI/bge-m3"),
            rerank_model_name=os.getenv("RERANK_MODEL_NAME", "BAAI/bge-reranker-v2-m3"),
            embed_dimension=int(os.getenv("EMBED_DIMENSION", "1024")),
            embed_max_length=int(os.getenv("EMBED_MAX_LENGTH", "8192")),
            rerank_max_length=int(os.getenv("RERANK_MAX_LENGTH", "1024")),
            embedding_batch_size=int(os.getenv("EMBED_BATCH_SIZE", "8")),
            rerank_batch_size=int(os.getenv("RERANK_BATCH_SIZE", "16")),
            max_inputs=int(os.getenv("MAX_EMBED_INPUTS", "128")),
            max_documents=int(os.getenv("MAX_RERANK_DOCUMENTS", "128")),
            device=os.getenv("MODEL_DEVICE", "auto"),
            preload=_boolean("MODEL_PRELOAD", True),
        )
