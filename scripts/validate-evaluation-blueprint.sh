#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BLUEPRINT="${1:-${ROOT_DIR}/benchmarks/chinese-enterprise-rag-v1.blueprint.json}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
[[ -f "${BLUEPRINT}" ]] || { echo "Blueprint not found: ${BLUEPRINT}" >&2; exit 1; }

jq empty "${BLUEPRINT}"

jq -e '
  .schemaVersion == "rag-evaluation-blueprint/v1"
  and ((.benchmarkId == null) or (.benchmarkId | type == "string" and length > 0 and length <= 160))
  and (.name | type == "string" and length > 0 and length <= 160)
  and (.description | type == "string" and length <= 4000)
  and (.knowledgeBase.name | type == "string" and length > 0)
  and (.expectations.caseCount | type == "number")
  and (.cases | type == "array")
  and ((.cases | length) == .expectations.caseCount)
  and (.documentSelectors | type == "object" and length > 0)
' "${BLUEPRINT}" >/dev/null || {
  echo "Blueprint header or expected case count is invalid" >&2
  exit 1
}

jq -e '
  ([.cases[].caseId] | length == (unique | length))
  and ([.cases[].question] | length == (unique | length))
  and all(.cases[];
    (.caseId | type == "string" and test("^[A-Z]+-[0-9]{3}$"))
    and (.question | type == "string" and length > 0 and length <= 8000)
    and (.expectedAnswer | type == "string" and length > 0 and length <= 16000)
    and (.expectedDocuments | type == "array")
    and (.metadata | type == "object")
    and (.metadata.category | type == "string" and length > 0)
    and (.metadata.recommendedMode == "FAST" or .metadata.recommendedMode == "DEEP")
    and (.metadata.expectNoAnswer | type == "boolean")
  )
' "${BLUEPRINT}" >/dev/null || {
  echo "Blueprint cases contain duplicate or malformed fields" >&2
  exit 1
}

jq -e '
  . as $root
  | all(.cases[];
      all(.expectedDocuments[]; $root.documentSelectors[.] != null)
      and all((.metadata.forbiddenDocumentAliases // [])[]; $root.documentSelectors[.] != null)
    )
  and all(.documentSelectors[];
      (.title | type == "string" and length > 0)
      and (.requireActive | type == "boolean")
    )
' "${BLUEPRINT}" >/dev/null || {
  echo "Blueprint references an unknown or malformed document selector" >&2
  exit 1
}

jq -e '
  ([.cases[].metadata.category] | unique) as $actual
  | (.expectations.categories | unique) as $expected
  | ($actual == $expected)
  and all(.cases[];
    ((.metadata.conversationGroup == null) == (.metadata.conversationTurn == null))
  )
  and (
    [.cases[]
      | select(.metadata.conversationGroup != null)
      | {group: .metadata.conversationGroup, turn: .metadata.conversationTurn}]
    | group_by(.group)
    | all(.[];
        (.[0].group | type == "string" and length > 0 and length <= 80)
        and ([.[].turn] == [range(1; length + 1)])
      )
  )
' "${BLUEPRINT}" >/dev/null || {
  echo "Blueprint categories or conversation turns are invalid" >&2
  exit 1
}

jq '{
  schemaVersion,
  name,
  cases: (.cases | length),
  categories: ([.cases[].metadata.category] | group_by(.) | map({category: .[0], cases: length})),
  conversationGroups: ([.cases[].metadata.conversationGroup | select(. != null)] | unique | length),
  noAnswerCases: ([.cases[] | select(.metadata.expectNoAnswer == true)] | length)
}' "${BLUEPRINT}"
