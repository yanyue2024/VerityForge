from __future__ import annotations

import asyncio
import logging
import time
import uuid
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException

from model_sidecar.config import Settings
from model_sidecar.models import EmbeddingRequest, RerankRequest
from model_sidecar.runtime import EmbeddingRuntime, RerankRuntime

LOGGER = logging.getLogger("model-sidecar")
settings = Settings.from_environment()
embedding_runtime = EmbeddingRuntime(settings)
rerank_runtime = RerankRuntime(settings)


@asynccontextmanager
async def lifespan(_: FastAPI):
    if settings.preload:
        LOGGER.info("Preloading local embedding and rerank models")
        # Transformers uses process-global lazy import state; concurrent first
        # imports can race. Sequential warmup also keeps peak GPU load lower.
        await asyncio.to_thread(embedding_runtime.load)
        await asyncio.to_thread(rerank_runtime.load)
        LOGGER.info("Local models are ready")
    yield


app = FastAPI(
    title="RAG Local Model Sidecar",
    version="0.1.0",
    lifespan=lifespan,
)


@app.get("/health")
def health() -> dict[str, Any]:
    devices = {embedding_runtime.device, rerank_runtime.device} - {"unresolved"}
    device = next(iter(devices)) if len(devices) == 1 else ("mixed" if devices else "unresolved")
    return {
        "status": "ok",
        "device": device,
        "embedding": {
            "name": settings.embed_model_name,
            "loaded": embedding_runtime.loaded,
            "dimension": settings.embed_dimension,
        },
        "rerank": {
            "name": settings.rerank_model_name,
            "loaded": rerank_runtime.loaded,
        },
    }


@app.get("/ready")
def ready() -> dict[str, Any]:
    if not embedding_runtime.loaded or not rerank_runtime.loaded:
        raise HTTPException(status_code=503, detail="models are not loaded")
    return {"status": "ready"}


@app.post("/v1/embeddings")
def embeddings(request: EmbeddingRequest) -> dict[str, Any]:
    texts = [request.input] if isinstance(request.input, str) else request.input
    if len(texts) > settings.max_inputs:
        raise HTTPException(status_code=413, detail="embedding input limit exceeded")
    if request.dimensions is not None and request.dimensions != settings.embed_dimension:
        raise HTTPException(status_code=400, detail="requested dimensions do not match the model")
    try:
        vectors = embedding_runtime.embed(texts)
    except Exception as exception:  # noqa: BLE001
        LOGGER.exception("Embedding inference failed")
        raise HTTPException(status_code=500, detail="embedding inference failed") from exception
    estimated_tokens = sum(max(1, len(text) // 2) for text in texts)
    return {
        "object": "list",
        "data": [
            {"object": "embedding", "index": index, "embedding": vector}
            for index, vector in enumerate(vectors)
        ],
        "model": request.model or settings.embed_model_name,
        "usage": {"prompt_tokens": estimated_tokens, "total_tokens": estimated_tokens},
    }


@app.post("/rerank")
def rerank(request: RerankRequest) -> dict[str, Any]:
    if len(request.documents) > settings.max_documents:
        raise HTTPException(status_code=413, detail="rerank document limit exceeded")
    started = time.monotonic()
    try:
        results = rerank_runtime.rerank(request.query, request.documents)
    except Exception as exception:  # noqa: BLE001
        LOGGER.exception("Rerank inference failed")
        raise HTTPException(status_code=500, detail="rerank inference failed") from exception
    if request.top_n is not None:
        results = results[: request.top_n]
    estimated_tokens = max(1, len(request.query) // 2) + sum(
        max(1, len(document) // 2) for document in request.documents
    )
    return {
        "id": f"rerank-{uuid.uuid4()}",
        "model": request.model or settings.rerank_model_name,
        "results": results,
        "usage": {
            "total_tokens": estimated_tokens,
            "latency_ms": int((time.monotonic() - started) * 1000),
        },
    }
