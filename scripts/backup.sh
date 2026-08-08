#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

umask 077
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_ROOT="${RAG_BACKUP_ROOT:-${ROOT_DIR}/tmp/backups}"
BACKUP_DIR="${1:-${BACKUP_ROOT}/rag-${STAMP}}"
if [[ "${BACKUP_DIR}" != /* ]]; then BACKUP_DIR="${ROOT_DIR}/${BACKUP_DIR}"; fi
POSTGRES_CONTAINER="${RAG_POSTGRES_CONTAINER:-rag-platform-postgres-1}"
MINIO_NETWORK="${RAG_COMPOSE_NETWORK:-rag-platform_default}"
POSTGRES_DB="${POSTGRES_DB:-rag}"
POSTGRES_USER="${POSTGRES_USER:-rag}"
MINIO_BUCKET="${MINIO_BUCKET:-rag-assets}"

mkdir -p "${BACKUP_DIR}/minio"
docker exec "${POSTGRES_CONTAINER}" pg_dump \
  --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
  --format custom --no-owner --no-privileges > "${BACKUP_DIR}/database.dump"

docker run --rm --network "${MINIO_NETWORK}" \
  --entrypoint /bin/sh \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}" \
  -e MINIO_BUCKET="${MINIO_BUCKET}" \
  -v "${BACKUP_DIR}/minio:/backup" \
  quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z \
  -c 'mc alias set source http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null &&
      mc mirror --overwrite "source/$MINIO_BUCKET" /backup >/dev/null'

cat > "${BACKUP_DIR}/manifest.txt" <<EOF
created_at=${STAMP}
database=${POSTGRES_DB}
object_bucket=${MINIO_BUCKET}
flyway_version=$(docker exec "${POSTGRES_CONTAINER}" psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" --tuples-only --no-align --command "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
EOF

(
  cd "${BACKUP_DIR}"
  find database.dump manifest.txt minio -type f -print0 \
    | sort -z \
    | xargs -0 sha256sum > SHA256SUMS
)

echo "Backup created: ${BACKUP_DIR}"
echo "Verify with: ${ROOT_DIR}/scripts/verify-backup.sh ${BACKUP_DIR}"
