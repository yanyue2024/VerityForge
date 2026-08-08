#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${1:?Usage: restore-drill.sh BACKUP_DIR RESTORE_DATABASE}"
if [[ "${BACKUP_DIR}" != /* ]]; then BACKUP_DIR="${ROOT_DIR}/${BACKUP_DIR}"; fi
RESTORE_DATABASE="${2:?Usage: restore-drill.sh BACKUP_DIR RESTORE_DATABASE}"
if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

POSTGRES_CONTAINER="${RAG_POSTGRES_CONTAINER:-rag-platform-postgres-1}"
POSTGRES_USER="${POSTGRES_USER:-rag}"
PRODUCTION_DATABASE="${POSTGRES_DB:-rag}"

if [[ "${RESTORE_DATABASE}" == "${PRODUCTION_DATABASE}" ]]; then
  echo "Refusing to restore over the configured production database: ${PRODUCTION_DATABASE}" >&2
  exit 2
fi
if ! [[ "${RESTORE_DATABASE}" =~ ^[a-zA-Z][a-zA-Z0-9_]{0,62}$ ]]; then
  echo "Restore database must be a simple PostgreSQL identifier." >&2
  exit 2
fi

"${ROOT_DIR}/scripts/verify-backup.sh" "${BACKUP_DIR}"
if docker exec "${POSTGRES_CONTAINER}" psql --username "${POSTGRES_USER}" --dbname postgres \
  --tuples-only --no-align --command "SELECT 1 FROM pg_database WHERE datname = '${RESTORE_DATABASE}'" \
  | grep -qx 1; then
  echo "Restore database already exists; choose a new name: ${RESTORE_DATABASE}" >&2
  exit 2
fi

docker exec "${POSTGRES_CONTAINER}" createdb --username "${POSTGRES_USER}" "${RESTORE_DATABASE}"
cleanup() {
  if [[ "${RAG_KEEP_RESTORE_DATABASE:-false}" != "true" ]]; then
    docker exec "${POSTGRES_CONTAINER}" dropdb --username "${POSTGRES_USER}" \
      --if-exists "${RESTORE_DATABASE}" >/dev/null
  fi
}
trap cleanup EXIT

docker exec -i "${POSTGRES_CONTAINER}" pg_restore \
  --username "${POSTGRES_USER}" --dbname "${RESTORE_DATABASE}" \
  --no-owner --no-privileges --exit-on-error < "${BACKUP_DIR}/database.dump"

FLYWAY_VERSION="$(docker exec "${POSTGRES_CONTAINER}" psql --username "${POSTGRES_USER}" \
  --dbname "${RESTORE_DATABASE}" --tuples-only --no-align \
  --command "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
DOCUMENT_COUNT="$(docker exec "${POSTGRES_CONTAINER}" psql --username "${POSTGRES_USER}" \
  --dbname "${RESTORE_DATABASE}" --tuples-only --no-align --command "SELECT count(*) FROM document")"

echo "Restore drill passed: database=${RESTORE_DATABASE} flyway=${FLYWAY_VERSION} documents=${DOCUMENT_COUNT}"
if [[ "${RAG_KEEP_RESTORE_DATABASE:-false}" == "true" ]]; then
  echo "Restore database retained because RAG_KEEP_RESTORE_DATABASE=true"
fi
