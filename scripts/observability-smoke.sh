#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

OTEL_HEALTH_PORT="${OTEL_HEALTH_PORT:-13133}"
PROMETHEUS_PORT="${PROMETHEUS_PORT:-9090}"
ALERTMANAGER_PORT="${ALERTMANAGER_PORT:-9093}"
TEMPO_PORT="${TEMPO_PORT:-3200}"
GRAFANA_PORT="${GRAFANA_PORT:-3000}"
GRAFANA_ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}"
: "${GRAFANA_ADMIN_PASSWORD:?Set GRAFANA_ADMIN_PASSWORD in .env}"

wait_url() {
  local name="$1" url="$2"
  for _ in $(seq 1 60); do
    if curl -fsS "${url}" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "${name} did not become ready at ${url}" >&2
  return 1
}

wait_url "OpenTelemetry Collector" "http://127.0.0.1:${OTEL_HEALTH_PORT}/"
wait_url "Prometheus" "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready"
wait_url "Alertmanager" "http://127.0.0.1:${ALERTMANAGER_PORT}/-/ready"
wait_url "Tempo" "http://127.0.0.1:${TEMPO_PORT}/ready"
wait_url "Grafana" "http://127.0.0.1:${GRAFANA_PORT}/api/health"

targets=$(curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/targets")
jq -e '
  [.data.activeTargets[] | select(.labels.job as $job
    | ["rag-application-otlp", "otel-collector-internal", "prometheus", "tempo", "alertmanager"]
    | index($job))]
  | length == 5 and all(.[]; .health == "up")
' <<<"${targets}" >/dev/null

dashboard_count=$(curl -fsS -u "${GRAFANA_ADMIN_USER}:${GRAFANA_ADMIN_PASSWORD}" \
  "http://127.0.0.1:${GRAFANA_PORT}/api/search?query=RAG%20Platform%20Overview" \
  | jq '[.[] | select(.uid == "rag-platform-overview")] | length')
[[ "${dashboard_count}" -eq 1 ]] || { echo "Provisioned Grafana dashboard was not found" >&2; exit 1; }

for service in rag-api rag-worker; do
  query=$(jq -rn --arg service "${service}" '"count(jvm_info{service_name=\"" + $service + "\"})" | @uri')
  value=$(curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/query?query=${query}" \
    | jq -r '.data.result[0].value[1] // "0"')
  awk -v value="${value}" 'BEGIN { exit !(value >= 1) }' \
    || { echo "No OTLP metrics received from ${service}" >&2; exit 1; }
done

for service in tempo otel-collector prometheus alertmanager grafana; do
  bindings=$(docker inspect "rag-platform-${service}-1" \
    --format '{{range $port, $items := .NetworkSettings.Ports}}{{range $items}}{{$port}}={{.HostIp}}:{{.HostPort}} {{end}}{{end}}')
  if grep -Eq '(^| )[^=]+=(0\.0\.0\.0|::):' <<<"${bindings}"; then
    echo "${service} exposes a management port beyond loopback: ${bindings}" >&2
    exit 1
  fi
done

jq -n \
  --arg collector "UP" --arg prometheus "UP" --arg alertmanager "UP" \
  --arg tempo "UP" --arg grafana "UP" \
  '{collector:$collector,prometheus:$prometheus,alertmanager:$alertmanager,tempo:$tempo,grafana:$grafana,applicationMetrics:["rag-api","rag-worker"],managementPorts:"loopback-only"}'
