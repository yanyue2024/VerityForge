#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

: "${GRAFANA_ADMIN_PASSWORD:?Set GRAFANA_ADMIN_PASSWORD in .env}"
case "${GRAFANA_ADMIN_PASSWORD}" in
  admin|ChangeThisGrafanaPassword\!|change-me-before-enabling-observability)
    echo "Refusing to start Grafana with a documented default password" >&2
    exit 2
    ;;
esac
[[ "${RAG_OTLP_METRICS_ENABLED:-false}" == true ]] \
  || { echo "Set RAG_OTLP_METRICS_ENABLED=true in .env" >&2; exit 2; }
[[ "${RAG_OTLP_TRACING_ENABLED:-false}" == true ]] \
  || { echo "Set RAG_OTLP_TRACING_ENABLED=true in .env" >&2; exit 2; }

"${ROOT_DIR}/scripts/validate-observability.sh"
docker compose --project-directory "${ROOT_DIR}" --profile observability up -d \
  tempo otel-collector alertmanager prometheus grafana
"${ROOT_DIR}/scripts/observability-smoke.sh"

echo "Grafana: http://127.0.0.1:${GRAFANA_PORT:-3000}"
echo "Prometheus: http://127.0.0.1:${PROMETHEUS_PORT:-9090}"
echo "Tempo API: http://127.0.0.1:${TEMPO_PORT:-3200}"
