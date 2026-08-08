# RAG Model Sidecar

This service exposes the local BGE models through HTTP without coupling the RAG
API or Worker to a Python runtime. The default embedding checkpoint is
`BAAI/bge-m3`, producing normalized 1024-dimensional dense vectors with an
8192-token input limit. The default reranker remains
`BAAI/bge-reranker-v2-m3`.

## Endpoints

- `GET /health`: model names, device, dimension, and load state.
- `GET /ready`: readiness check; returns 503 until both models are loaded.
- `POST /v1/embeddings`: OpenAI-compatible embedding response.
- `POST /rerank`: query/document cross-encoder reranking.

The model directories are mounted read-only at `/models/embedding` and
`/models/reranker`. Paths are never returned by the health endpoint. By default
both models preload during startup so a ready instance does not incur a first
request load penalty. Startup also verifies that the embedding checkpoint's
hidden size matches `EMBED_DIMENSION`; a wrong mount therefore fails readiness
instead of producing an incompatible Index Generation.

`bge-m3` is substantially larger than the previous small checkpoint. The
default sidecar embedding batch size is 8 and can be tuned with
`EMBED_BATCH_SIZE` after measuring GPU memory usage.

Run lightweight API tests without loading PyTorch models:

```bash
python -m pip install -e '.[test]'
MODEL_PRELOAD=false pytest
```

The production container uses the PyTorch CUDA 12.1 runtime. GPU selection is
controlled by `NVIDIA_VISIBLE_DEVICES`; the default Compose configuration binds
the service to GPU 0.
