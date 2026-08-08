from __future__ import annotations

# Adapted from WeKnora scripts/local_bge_model_server.py.
# Copyright (C) 2025 Tencent. Licensed under the MIT License.

import threading
from collections.abc import Iterable
from typing import Any

from model_sidecar.config import Settings


def _batches(values: list[Any], size: int) -> Iterable[list[Any]]:
    for start in range(0, len(values), size):
        yield values[start : start + size]


def _resolve_device(torch: Any, configured: str) -> Any:
    if configured != "auto":
        return torch.device(configured)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def _validate_embedding_dimension(model: Any, expected: int) -> None:
    actual = getattr(model.config, "hidden_size", None)
    if actual != expected:
        raise RuntimeError(
            "embedding checkpoint dimension does not match EMBED_DIMENSION: "
            f"expected {expected}, got {actual}"
        )


class EmbeddingRuntime:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._lock = threading.Lock()
        self._loaded = False
        self._device = "unresolved"
        self._torch: Any = None
        self._functional: Any = None
        self._tokenizer: Any = None
        self._model: Any = None

    @property
    def loaded(self) -> bool:
        return self._loaded

    @property
    def device(self) -> str:
        return self._device

    def load(self) -> None:
        if self._loaded:
            return
        with self._lock:
            if self._loaded:
                return
            import torch
            import torch.nn.functional as functional
            from transformers import AutoModel, AutoTokenizer

            device = _resolve_device(torch, self.settings.device)
            tokenizer = AutoTokenizer.from_pretrained(
                self.settings.embed_model_path,
                local_files_only=True,
            )
            model = AutoModel.from_pretrained(
                self.settings.embed_model_path,
                local_files_only=True,
            )
            _validate_embedding_dimension(model, self.settings.embed_dimension)
            model.to(device)
            model.eval()
            self._torch = torch
            self._functional = functional
            self._tokenizer = tokenizer
            self._model = model
            self._device = str(device)
            self._loaded = True

    def embed(self, texts: list[str]) -> list[list[float]]:
        self.load()
        vectors: list[list[float]] = []
        with self._torch.inference_mode():
            for batch in _batches(texts, self.settings.embedding_batch_size):
                inputs = self._tokenizer(
                    batch,
                    padding=True,
                    truncation=True,
                    max_length=self.settings.embed_max_length,
                    return_tensors="pt",
                ).to(self._device)
                outputs = self._model(**inputs, return_dict=True)
                batch_vectors = outputs.last_hidden_state[:, 0]
                batch_vectors = self._functional.normalize(batch_vectors, p=2, dim=1)
                values = batch_vectors.detach().cpu().float().tolist()
                if any(len(vector) != self.settings.embed_dimension for vector in values):
                    raise RuntimeError("embedding model returned an unexpected dimension")
                vectors.extend(values)
        return vectors


class RerankRuntime:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._lock = threading.Lock()
        self._loaded = False
        self._device = "unresolved"
        self._torch: Any = None
        self._tokenizer: Any = None
        self._model: Any = None

    @property
    def loaded(self) -> bool:
        return self._loaded

    @property
    def device(self) -> str:
        return self._device

    def load(self) -> None:
        if self._loaded:
            return
        with self._lock:
            if self._loaded:
                return
            import torch
            from transformers import AutoModelForSequenceClassification, AutoTokenizer

            device = _resolve_device(torch, self.settings.device)
            tokenizer = AutoTokenizer.from_pretrained(
                self.settings.rerank_model_path,
                local_files_only=True,
            )
            model = AutoModelForSequenceClassification.from_pretrained(
                self.settings.rerank_model_path,
                local_files_only=True,
            )
            model.to(device)
            model.eval()
            self._torch = torch
            self._tokenizer = tokenizer
            self._model = model
            self._device = str(device)
            self._loaded = True

    def rerank(self, query: str, documents: list[str]) -> list[dict[str, Any]]:
        self.load()
        indexed_documents = list(enumerate(documents))
        scores: list[tuple[int, str, float]] = []
        with self._torch.inference_mode():
            for batch in _batches(indexed_documents, self.settings.rerank_batch_size):
                pairs = [[query, document] for _, document in batch]
                inputs = self._tokenizer(
                    pairs,
                    padding=True,
                    truncation=True,
                    max_length=self.settings.rerank_max_length,
                    return_tensors="pt",
                ).to(self._device)
                logits = self._model(**inputs, return_dict=True).logits.view(-1).float()
                probabilities = self._torch.sigmoid(logits).detach().cpu().tolist()
                scores.extend(
                    (index, document, float(score))
                    for (index, document), score in zip(batch, probabilities, strict=True)
                )
        scores.sort(key=lambda item: item[2], reverse=True)
        return [
            {
                "index": index,
                "document": {"text": document},
                "relevance_score": score,
            }
            for index, document, score in scores
        ]
