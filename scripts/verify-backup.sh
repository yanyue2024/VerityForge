#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${1:?Usage: verify-backup.sh BACKUP_DIR}"
if [[ "${BACKUP_DIR}" != /* ]]; then BACKUP_DIR="${ROOT_DIR}/${BACKUP_DIR}"; fi
POSTGRES_CONTAINER="${RAG_POSTGRES_CONTAINER:-rag-platform-postgres-1}"

test -s "${BACKUP_DIR}/database.dump"
test -s "${BACKUP_DIR}/manifest.txt"
test -s "${BACKUP_DIR}/SHA256SUMS"
(
  cd "${BACKUP_DIR}"
  sha256sum --check SHA256SUMS
)
docker exec -i "${POSTGRES_CONTAINER}" pg_restore --list < "${BACKUP_DIR}/database.dump" >/dev/null

echo "Backup is internally consistent: ${BACKUP_DIR}"
