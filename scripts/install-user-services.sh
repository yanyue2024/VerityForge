#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UNIT_DIR="${HOME}/.config/systemd/user"
mkdir -p "${UNIT_DIR}"

for service in rag-api rag-worker; do
  sed "s|@ROOT@|${ROOT_DIR}|g" \
    "${ROOT_DIR}/deploy/systemd/${service}.service.in" > "${UNIT_DIR}/${service}.service"
done

systemctl --user daemon-reload
systemctl --user enable rag-api.service rag-worker.service
echo "User services installed. Restart after packaging with:"
echo "  ./scripts/deploy-local.sh"
