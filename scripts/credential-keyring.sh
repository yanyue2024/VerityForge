#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${RAG_ENV_FILE:-${ROOT_DIR}/.env}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/credential-keyring.sh prepare <new-key-id>
  ./scripts/credential-keyring.sh finalize <rotation-status.json>

prepare creates a new active AES-256 key and keeps the previous key for decryption.
finalize removes fallback keys only after an API status response proves rotation is complete.
EOF
}

read_env() {
  local key="$1"
  awk -v key="${key}" '
    index($0, key "=") == 1 {
      print substr($0, length(key) + 2)
      exit
    }
  ' "${ENV_FILE}"
}

write_env() {
  local key="$1"
  local value="$2"
  local temporary
  temporary="$(mktemp "${ENV_FILE}.XXXXXX")"
  chmod 600 "${temporary}"
  awk -v key="${key}" -v value="${value}" '
    BEGIN { found = 0 }
    index($0, key "=") == 1 {
      print key "=" value
      found = 1
      next
    }
    { print }
    END {
      if (!found) print key "=" value
    }
  ' "${ENV_FILE}" > "${temporary}"
  mv "${temporary}" "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
}

backup_env() {
  local phase="$1"
  local directory="${ROOT_DIR}/tmp/credential-rotation/$(date -u +%Y%m%dT%H%M%SZ)-${phase}"
  mkdir -p "${directory}"
  chmod 700 "${directory}"
  install -m 600 "${ENV_FILE}" "${directory}/env.backup"
  printf '%s\n' "${directory}/env.backup"
}

validate_key() {
  local key="$1"
  local byte_count
  if ! byte_count="$(printf '%s' "${key}" | base64 --decode 2>/dev/null | wc -c | tr -d ' ')"; then
    echo "Configured RAG_CREDENTIAL_MASTER_KEY is not valid Base64" >&2
    exit 1
  fi
  if [[ "${byte_count}" != "32" ]]; then
    echo "Configured RAG_CREDENTIAL_MASTER_KEY is not Base64 for exactly 32 bytes" >&2
    exit 1
  fi
}

prepare() {
  local new_key_id="${1:-}"
  if [[ ! "${new_key_id}" =~ ^[A-Za-z0-9._-]{1,40}$ ]]; then
    echo "New key id must match [A-Za-z0-9._-]{1,40}" >&2
    exit 1
  fi
  local current_key_id current_key current_fallbacks new_key backup
  current_key_id="$(read_env RAG_CREDENTIAL_ACTIVE_KEY_ID)"
  current_key_id="${current_key_id:-primary}"
  current_key="$(read_env RAG_CREDENTIAL_MASTER_KEY)"
  current_fallbacks="$(read_env RAG_CREDENTIAL_DECRYPTION_KEYS)"
  if [[ -z "${current_key}" ]]; then
    echo "RAG_CREDENTIAL_MASTER_KEY is missing from ${ENV_FILE}" >&2
    exit 1
  fi
  validate_key "${current_key}"
  if [[ "${new_key_id}" == "${current_key_id}" ]]; then
    echo "New key id must differ from the active key id" >&2
    exit 1
  fi
  if [[ ",${current_fallbacks}," == *",${new_key_id}="* ]]; then
    echo "New key id already exists in the decryption keyring" >&2
    exit 1
  fi

  backup="$(backup_env prepare)"
  new_key="$(openssl rand -base64 32 | tr -d '\n')"
  local fallback_entry="${current_key_id}=${current_key}"
  if [[ -n "${current_fallbacks}" ]]; then
    fallback_entry="${fallback_entry},${current_fallbacks}"
  fi
  write_env RAG_CREDENTIAL_ACTIVE_KEY_ID "${new_key_id}"
  write_env RAG_CREDENTIAL_MASTER_KEY "${new_key}"
  write_env RAG_CREDENTIAL_DECRYPTION_KEYS "${fallback_entry}"

  echo "Prepared credential keyring with active key id ${new_key_id}."
  echo "Private plaintext environment backup (mode 0600): ${backup}"
  echo "Restart API and Worker, verify unreadableCredentials is zero, then rotate from /security."
}

finalize() {
  local status_file="${1:-}"
  if [[ -z "${status_file}" || ! -f "${status_file}" ]]; then
    echo "A credential-rotation API status JSON file is required" >&2
    exit 1
  fi
  command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
  local active_key_id status_key_id pending unreadable backup
  active_key_id="$(read_env RAG_CREDENTIAL_ACTIVE_KEY_ID)"
  status_key_id="$(jq -er '.activeKeyId' "${status_file}")"
  pending="$(jq -er '.needsRotation' "${status_file}")"
  unreadable="$(jq -er '.unreadableCredentials' "${status_file}")"
  if [[ "${status_key_id}" != "${active_key_id}" ]]; then
    echo "Status Key ID does not match the local active Key ID" >&2
    exit 1
  fi
  if [[ "${pending}" != "0" || "${unreadable}" != "0" ]]; then
    echo "Rotation is incomplete; fallback keys were not removed" >&2
    exit 1
  fi

  backup="$(backup_env finalize)"
  write_env RAG_CREDENTIAL_DECRYPTION_KEYS ""
  echo "Removed decryption-only keys after verified rotation."
  echo "Private plaintext environment backup (mode 0600): ${backup}"
  echo "Restart API and Worker once more, then verify model and notification access."
}

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Environment file not found: ${ENV_FILE}" >&2
  exit 1
fi

case "${1:-}" in
  prepare)
    prepare "${2:-}"
    ;;
  finalize)
    finalize "${2:-}"
    ;;
  *)
    usage
    exit 1
    ;;
esac
