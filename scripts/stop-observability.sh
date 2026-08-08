#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
docker compose --project-directory "${ROOT_DIR}" --profile observability stop \
  grafana prometheus alertmanager otel-collector tempo
