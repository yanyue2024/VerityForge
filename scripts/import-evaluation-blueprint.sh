#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BLUEPRINT="${ROOT_DIR}/benchmarks/yanyue-operations-v1.blueprint.json"
OUTPUT=""
DRY_RUN=false
ALLOW_DUPLICATE=false

usage() {
  cat <<'EOF'
Usage: scripts/import-evaluation-blueprint.sh [options]

Options:
  --blueprint PATH     Blueprint to compile and import.
  --output PATH        Keep the compiled rag-evaluation-dataset/v1 JSON.
  --dry-run            Resolve documents and compile without importing.
  --allow-duplicate    Import even when a dataset with the same name exists.
  --help               Show this help.

Credentials are read from RAG_USERNAME/RAG_PASSWORD or the bootstrap values in .env.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --blueprint) BLUEPRINT="${2:?--blueprint requires a path}"; shift 2 ;;
    --output) OUTPUT="${2:?--output requires a path}"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --allow-duplicate) ALLOW_DUPLICATE=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
"${ROOT_DIR}/scripts/validate-evaluation-blueprint.sh" "${BLUEPRINT}" >/dev/null

if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

API_URL="${API_URL:-http://127.0.0.1:8080}"
USERNAME="${RAG_USERNAME:-${RAG_BOOTSTRAP_ADMIN_USERNAME:-admin}}"
PASSWORD="${RAG_PASSWORD:-${ADMIN_PASSWORD:-${RAG_BOOTSTRAP_ADMIN_PASSWORD:-}}}"
[[ -n "${PASSWORD}" ]] || {
  echo "Set RAG_PASSWORD or RAG_BOOTSTRAP_ADMIN_PASSWORD" >&2
  exit 1
}

login_payload=$(jq -nc --arg username "${USERNAME}" --arg password "${PASSWORD}" \
  '{username:$username,password:$password}')
token=$(curl -fsS -H 'Content-Type: application/json' --data "${login_payload}" \
  "${API_URL}/api/v1/auth/login" | jq -er '.accessToken')
auth=(-H "Authorization: Bearer ${token}")

knowledge_base_name=$(jq -er '.knowledgeBase.name' "${BLUEPRINT}")
benchmark_id=$(jq -er '.benchmarkId // "yanyue-operations-v1"' "${BLUEPRINT}")
knowledge_bases=$(curl -fsS "${auth[@]}" "${API_URL}/api/v1/knowledge-bases")
knowledge_base_matches=$(jq -c --arg name "${knowledge_base_name}" \
  '[.[] | select(.name == $name)]' <<<"${knowledge_bases}")
if [[ "$(jq 'length' <<<"${knowledge_base_matches}")" -ne 1 ]]; then
  echo "Expected exactly one knowledge base named '${knowledge_base_name}'" >&2
  exit 1
fi
knowledge_base_id=$(jq -er '.[0].id' <<<"${knowledge_base_matches}")
documents=$(curl -fsS "${auth[@]}" \
  "${API_URL}/api/v1/knowledge-bases/${knowledge_base_id}/documents")

resolved='{}'
while IFS=$'\t' read -r alias title require_active; do
  matches=$(jq -c --arg title "${title}" --argjson requireActive "${require_active}" \
    '[.[] | select(.title == $title and (($requireActive | not) or .status == "ACTIVE"))]' \
    <<<"${documents}")
  if [[ "$(jq 'length' <<<"${matches}")" -ne 1 ]]; then
    echo "Selector '${alias}' expected one matching document titled '${title}'" >&2
    exit 1
  fi
  document_id=$(jq -er '.[0].id' <<<"${matches}")
  resolved=$(jq -c --arg alias "${alias}" --arg id "${document_id}" \
    '. + {($alias): $id}' <<<"${resolved}")
done < <(jq -r '.documentSelectors | to_entries[] | [.key, .value.title, .value.requireActive] | @tsv' \
  "${BLUEPRINT}")

if [[ -n "${OUTPUT}" ]]; then
  mkdir -p "$(dirname "${OUTPUT}")"
  compiled="${OUTPUT}"
else
  compiled=$(mktemp "${TMPDIR:-/tmp}/rag-evaluation-dataset.XXXXXX.json")
  trap 'rm -f "${compiled}"' EXIT
fi

jq --argjson documentIds "${resolved}" \
   --arg knowledgeBaseId "${knowledge_base_id}" \
   --arg knowledgeBaseName "${knowledge_base_name}" \
   --arg benchmarkId "${benchmark_id}" \
   --arg exportedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
  {
    schemaVersion: "rag-evaluation-dataset/v1",
    sourceDatasetId: null,
    exportedAt: $exportedAt,
    name,
    description,
    cases: [.cases[] | {
      question,
      expectedAnswer,
      expectedDocumentIds: [.expectedDocuments[] | $documentIds[.]],
      metadata: (.metadata + {
        benchmarkCaseId: .caseId,
        benchmarkBlueprint: $benchmarkId,
        knowledgeBaseId: $knowledgeBaseId,
        knowledgeBaseName: $knowledgeBaseName,
        expectedDocumentAliases: .expectedDocuments,
        forbiddenDocumentIds: [(.metadata.forbiddenDocumentAliases // [])[] | $documentIds[.]]
      })
    }]
  }
' "${BLUEPRINT}" >"${compiled}"

jq -e --argjson expected "$(jq '.expectations.caseCount' "${BLUEPRINT}")" \
  '.schemaVersion == "rag-evaluation-dataset/v1" and (.cases | length == $expected)
   and all(.cases[]; all(.expectedDocumentIds[]; type == "string" and length > 0))' \
  "${compiled}" >/dev/null

printf 'Compiled %s cases for knowledge base %s (%s)\n' \
  "$(jq '.cases | length' "${compiled}")" "${knowledge_base_name}" "${knowledge_base_id}"
[[ -z "${OUTPUT}" ]] || printf 'Bundle: %s\n' "$(realpath "${compiled}")"

if [[ "${DRY_RUN}" == true ]]; then
  echo "Dry run complete; no dataset was imported."
  exit 0
fi

dataset_name=$(jq -er '.name' "${compiled}")
existing=$(curl -fsS "${auth[@]}" "${API_URL}/api/v1/evaluation/datasets" \
  | jq --arg name "${dataset_name}" '[.[] | select(.name == $name)] | length')
if [[ "${existing}" -gt 0 && "${ALLOW_DUPLICATE}" != true ]]; then
  echo "Dataset '${dataset_name}' already exists; use --allow-duplicate to import another copy" >&2
  exit 1
fi

result=$(curl -fsS "${auth[@]}" -H 'Content-Type: application/json' \
  --data-binary "@${compiled}" "${API_URL}/api/v1/evaluation/datasets/import")
jq '{id: .dataset.id, name: .dataset.name, cases: (.cases | length), runs: (.runs | length)}' <<<"${result}"
