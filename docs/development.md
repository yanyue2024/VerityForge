# Development

## Prerequisites

- Docker with Compose
- Node.js 22+
- Python 3.12 for the optional parser sidecar
- NVIDIA Container Toolkit and a CUDA-capable GPU for the local BGE model sidecar

The repository bootstraps a local Temurin JDK 25 and Maven Wrapper:

```bash
./scripts/bootstrap-toolchain.sh
```

## Infrastructure

```bash
cp .env.example .env
docker compose up -d postgres redis minio minio-init
```

Optional services:

```bash
docker compose --profile full up -d parser-sidecar
docker compose --profile local-models up -d ollama
docker compose --profile models up -d model-sidecar
```

`model-sidecar` mounts `BGE_EMBED_MODEL_PATH` and `BGE_RERANK_MODEL_PATH`
read-only. `BGE_EMBED_MODEL_PATH` must point to a complete local
`BAAI/bge-m3` checkpoint. The default host endpoint is
`http://localhost:18091`; GPU selection uses `MODEL_SIDECAR_GPU` and defaults
to GPU 0. The sidecar emits normalized 1024-dimensional dense vectors and
checks the mounted checkpoint dimension during startup.

Switching an existing deployment requires a new tested Embedding Profile and a
new 1024-dimensional Index Generation. Keep the old embedding endpoint running
until the new Generation is fully built and activated; the previous active
Generation still needs its original model for query embeddings during the
rebuild.

Generate the credential master key before starting the API. It must be Base64
for exactly 32 random bytes:

```bash
openssl rand -base64 32
```

The API stores model and Evaluation webhook keys as versioned AES-256-GCM envelopes. New envelopes include the
configured `RAG_CREDENTIAL_ACTIVE_KEY_ID`; `RAG_CREDENTIAL_DECRYPTION_KEYS` holds comma-separated
`key-id=base64` fallback keys during a rotation. Model Profile
responses expose only `hasApiKey`; updating a Profile without an `apiKey`
preserves its current credential.

## Backend

Start these in separate terminals:

```bash
./mvnw verify
./mvnw -pl apps/rag-api -am spring-boot:run
./mvnw -pl apps/rag-worker -am spring-boot:run
```

## Frontend

```bash
cd web
npm install
npm run dev
```

The frontend proxies `/api` to `http://localhost:8080`. Override it with `VITE_DEV_API_TARGET` when the API uses a
different port.

## Verification

```bash
./mvnw test

cd web
npm run typecheck
npm run build
npm run test:e2e

cd ../parser-sidecar
python3.12 -m venv .venv
. .venv/bin/activate
pip install ".[test]"
ruff check src tests
pytest

cd ../model-sidecar
python3 -m venv .venv
. .venv/bin/activate
pip install ".[test]"
MODEL_PRELOAD=false pytest
```

For a Python-version-independent Sidecar check:

```bash
docker build --build-arg 'INSTALL_EXTRAS=[test]' -t rag-parser-sidecar:test-suite parser-sidecar
docker run --rm --user root \
  -v "$PWD/parser-sidecar/tests:/app/tests:ro" \
  rag-parser-sidecar:test-suite \
  sh -lc 'ruff check src tests && pytest'
```

## Application images

The Java images package the already verified Spring Boot JARs, keeping Maven dependency resolution outside the
runtime image build:

```bash
./mvnw -DskipTests package
docker compose --profile app build rag-api rag-worker web
docker compose --profile app up
```
