#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OBS_DIR="${ROOT_DIR}/deploy/observability"
STATIC_ONLY=false
[[ "${1:-}" != "--static" ]] || STATIC_ONLY=true
if [[ $# -gt 1 || ($# -eq 1 && "${1}" != "--static") ]]; then
  echo "Usage: scripts/validate-observability.sh [--static]" >&2
  exit 2
fi

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v rg >/dev/null || { echo "ripgrep is required" >&2; exit 1; }

export BGE_EMBED_MODEL_PATH="${BGE_EMBED_MODEL_PATH:-/tmp/rag-embed-model}"
export BGE_RERANK_MODEL_PATH="${BGE_RERANK_MODEL_PATH:-/tmp/rag-rerank-model}"
export RAG_CREDENTIAL_MASTER_KEY="${RAG_CREDENTIAL_MASTER_KEY:-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}"

docker compose --project-directory "${ROOT_DIR}" --profile observability config --quiet
jq -e '
  .uid == "rag-platform-overview"
  and .title == "RAG Platform Overview"
  and (.panels | length >= 12)
  and ([.panels[].id] | length == (unique | length))
  and ([.. | objects | .expr? // empty] | any(contains("rag_run_total")))
  and ([.. | objects | .expr? // empty] | all(contains("_total_total") | not))
  and all(.panels[]; .datasource.uid == "rag-prometheus")
' "${OBS_DIR}/grafana/dashboards/rag-platform-overview.json" >/dev/null

if rg -n '_total_total' "${OBS_DIR}/alerts.yml" \
  "${OBS_DIR}/grafana/dashboards/rag-platform-overview.json"; then
  echo "OTLP counters already carry one _total suffix; remove the duplicate suffix above" >&2
  exit 1
fi

for file in \
  "${OBS_DIR}/otel-collector.yml" \
  "${OBS_DIR}/tempo.yml" \
  "${OBS_DIR}/prometheus.yml" \
  "${OBS_DIR}/alerts.yml" \
  "${OBS_DIR}/alertmanager.yml" \
  "${OBS_DIR}/grafana/provisioning/datasources/rag.yml" \
  "${OBS_DIR}/grafana/provisioning/dashboards/rag.yml"; do
  [[ -s "${file}" ]] || { echo "Missing observability configuration: ${file}" >&2; exit 1; }
done

if [[ "${STATIC_ONLY}" == true ]]; then
  echo "Observability static configuration is valid."
  exit 0
fi

docker run --rm \
  -v "${OBS_DIR}/otel-collector.yml:/etc/otelcol-contrib/config.yml:ro" \
  otel/opentelemetry-collector-contrib:0.156.0 \
  validate --config=/etc/otelcol-contrib/config.yml

docker run --rm \
  -v "${OBS_DIR}/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  -v "${OBS_DIR}/alerts.yml:/etc/prometheus/alerts.yml:ro" \
  --entrypoint /bin/promtool \
  prom/prometheus:v3.13.1 \
  check config /etc/prometheus/prometheus.yml

docker run --rm \
  -v "${OBS_DIR}/alerts.yml:/etc/prometheus/alerts.yml:ro" \
  --entrypoint /bin/promtool \
  prom/prometheus:v3.13.1 \
  check rules /etc/prometheus/alerts.yml

docker run --rm \
  -v "${OBS_DIR}/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  --entrypoint /bin/amtool \
  prom/alertmanager:v0.33.1 \
  check-config /etc/alertmanager/alertmanager.yml

docker run --rm \
  -v "${OBS_DIR}/tempo.yml:/etc/tempo/tempo.yml:ro" \
  grafana/tempo:3.0.2 \
  -target=all -config.file=/etc/tempo/tempo.yml -config.verify=true

echo "Observability configuration is valid."
