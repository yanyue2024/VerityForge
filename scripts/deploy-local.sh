#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${ROOT_DIR}/.runtime"
RELEASES_DIR="${RUNTIME_DIR}/releases"
API_JAR="${ROOT_DIR}/apps/rag-api/target/rag-api-0.1.0-SNAPSHOT.jar"
WORKER_JAR="${ROOT_DIR}/apps/rag-worker/target/rag-worker-0.1.0-SNAPSHOT.jar"

if [[ "${1:-}" != "--skip-build" ]]; then
  "${ROOT_DIR}/mvnw" -DskipTests package
fi

for artifact in "${API_JAR}" "${WORKER_JAR}"; do
  if [[ ! -s "${artifact}" ]]; then
    echo "Missing packaged artifact: ${artifact}" >&2
    exit 1
  fi
done

STAMP="$(date -u +%Y%m%dT%H%M%SZ)-$$"
RELEASE_DIR="${RELEASES_DIR}/${STAMP}"
mkdir -p "${RELEASE_DIR}"
install -m 0444 "${API_JAR}" "${RELEASE_DIR}/rag-api.jar"
install -m 0444 "${WORKER_JAR}" "${RELEASE_DIR}/rag-worker.jar"

ln -sfn "${RELEASE_DIR}" "${RUNTIME_DIR}/current.next"
mv -Tf "${RUNTIME_DIR}/current.next" "${RUNTIME_DIR}/current"

"${ROOT_DIR}/scripts/install-user-services.sh" >/dev/null
systemctl --user restart rag-api.service rag-worker.service

for attempt in $(seq 1 75); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    echo "Local release active: ${RELEASE_DIR}"
    exit 0
  fi
  sleep 1
done

echo "API did not become healthy after deploying ${RELEASE_DIR}" >&2
exit 1
