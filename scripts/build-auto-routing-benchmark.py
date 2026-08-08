#!/usr/bin/env python3
"""Build the balanced 200-case benchmark used to evaluate AUTO routing."""

from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[1]
BASE_BLUEPRINT = ROOT / "benchmarks" / "chinese-enterprise-rag-v1.blueprint.json"
AGENTIC_BLUEPRINT = (
    ROOT / "benchmarks" / "chinese-enterprise-agentic-retrieval-v1.blueprint.json"
)
DEFAULT_OUTPUT = ROOT / "benchmarks" / "chinese-enterprise-auto-routing-v1.blueprint.json"

SOURCE_ORDER = ("openeuler", "kubernetes", "ant-design", "apache-doris")
SOURCE_FORMATS = ("pdf", "docx", "html", "md")
FAST_FACT_CATEGORIES = ("direct_fact", "procedure_condition")

# The base benchmark contributes its 24 genuine cross-document DEEP cases. These
# quotas select the remaining 76 cases from the hard Agentic benchmark while
# keeping every source at 19 cases and preserving the source benchmark's
# challenge-type proportions. Single-intent no-answer cases stay in FAST.
AGENTIC_DEEP_QUOTAS: dict[str, dict[str, int]] = {
    "openeuler": {
        "multi_intent": 8,
        "query_decomposition": 6,
        "semantic_paraphrase": 3,
        "keyword_sparse": 2,
    },
    "kubernetes": {
        "multi_intent": 8,
        "query_decomposition": 6,
        "semantic_paraphrase": 3,
        "keyword_sparse": 2,
    },
    "ant-design": {
        "multi_intent": 7,
        "query_decomposition": 6,
        "semantic_paraphrase": 4,
        "keyword_sparse": 2,
    },
    "apache-doris": {
        "multi_intent": 7,
        "query_decomposition": 5,
        "semantic_paraphrase": 5,
        "keyword_sparse": 2,
    },
}


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise RuntimeError(f"Expected a JSON object in {path}")
    return value


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def stable_rank(benchmark_id: str, case: dict[str, Any]) -> str:
    source_case_id = case.get("caseId")
    if not isinstance(source_case_id, str) or not source_case_id:
        raise RuntimeError(f"Case without a stable caseId in {benchmark_id}")
    material = f"auto-routing-v1\0{benchmark_id}\0{source_case_id}".encode()
    return hashlib.sha256(material).hexdigest()


def source_format(case: dict[str, Any]) -> str:
    return canonical(case["metadata"].get("sourceFormat"))


def balanced_pick(
    candidates: list[dict[str, Any]],
    count: int,
    benchmark_id: str,
    diversity_key: Callable[[dict[str, Any]], str] = source_format,
) -> list[dict[str, Any]]:
    """Pick deterministically while spreading formats and document aliases."""
    if len(candidates) < count:
        raise RuntimeError(
            f"Cannot select {count} cases from {len(candidates)} candidates in {benchmark_id}"
        )

    remaining = list(candidates)
    selected: list[dict[str, Any]] = []
    diversity_counts: Counter[str] = Counter()
    document_counts: Counter[str] = Counter()
    while len(selected) < count:
        candidate = min(
            remaining,
            key=lambda case: (
                diversity_counts[diversity_key(case)],
                sum(document_counts[alias] for alias in case["expectedDocuments"]),
                stable_rank(benchmark_id, case),
            ),
        )
        remaining.remove(candidate)
        selected.append(candidate)
        diversity_counts[diversity_key(candidate)] += 1
        document_counts.update(candidate["expectedDocuments"])
    return selected


def select_fast(base: dict[str, Any]) -> list[dict[str, Any]]:
    benchmark_id = base["benchmarkId"]
    base_fast = [
        case for case in base["cases"] if case["metadata"].get("recommendedMode") == "FAST"
    ]
    selected: list[dict[str, Any]] = []

    # 4 projects x 2 categories x 4 formats x 3 cases = 96 answerable cases.
    for source in SOURCE_ORDER:
        for category in FAST_FACT_CATEGORIES:
            for fmt in SOURCE_FORMATS:
                candidates = [
                    case
                    for case in base_fast
                    if case["metadata"].get("sourceProject") == source
                    and case["metadata"].get("category") == category
                    and case["metadata"].get("sourceFormat") == fmt
                ]
                selected.extend(
                    balanced_pick(candidates, 3, benchmark_id, lambda case: case["caseId"])
                )

        # Add one negative-rejection case per project without allowing all eight
        # source negatives to dominate a routing benchmark.
        negatives = [
            case
            for case in base_fast
            if case["metadata"].get("sourceProject") == source
            and case["metadata"].get("category") == "no_answer"
            and case["metadata"].get("expectNoAnswer") is True
        ]
        selected.extend(balanced_pick(negatives, 1, benchmark_id))

    if len(selected) != 100:
        raise RuntimeError(f"Expected exactly 100 FAST cases, selected {len(selected)}")
    return selected


def select_deep(base: dict[str, Any], agentic: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
    base_deep = [
        case
        for case in base["cases"]
        if case["metadata"].get("recommendedMode") == "DEEP"
        and case["metadata"].get("category") == "cross_document"
    ]
    if len(base_deep) != 24:
        raise RuntimeError(f"Expected 24 base cross-document DEEP cases, found {len(base_deep)}")

    selected: list[tuple[str, dict[str, Any]]] = [
        (base["benchmarkId"], case) for case in base_deep
    ]
    for source in SOURCE_ORDER:
        for challenge_type, quota in AGENTIC_DEEP_QUOTAS[source].items():
            candidates = [
                case
                for case in agentic["cases"]
                if case["metadata"].get("recommendedMode") == "DEEP"
                and case["metadata"].get("difficulty") == "hard"
                and case["metadata"].get("sourceProject") == source
                and case["metadata"].get("challengeType") == challenge_type
            ]
            picked = balanced_pick(candidates, quota, agentic["benchmarkId"])
            selected.extend((agentic["benchmarkId"], case) for case in picked)

    if len(selected) != 100:
        raise RuntimeError(f"Expected exactly 100 DEEP cases, selected {len(selected)}")
    return selected


def merge_document_selectors(*blueprints: dict[str, Any]) -> dict[str, Any]:
    merged: dict[str, Any] = {}
    for blueprint in blueprints:
        for alias, selector in blueprint["documentSelectors"].items():
            if alias in merged and merged[alias] != selector:
                raise RuntimeError(
                    f"Conflicting document selector {alias!r} in {blueprint['benchmarkId']}"
                )
            merged[alias] = deepcopy(selector)
    return {alias: merged[alias] for alias in sorted(merged)}


def copy_case(
    source_benchmark_id: str,
    source_case: dict[str, Any],
    output_case_id: str,
) -> dict[str, Any]:
    result = deepcopy(source_case)
    original_mode = source_case["metadata"].get("recommendedMode")
    result["caseId"] = output_case_id
    result["metadata"]["routingSourceBenchmarkId"] = source_benchmark_id
    result["metadata"]["routingSourceCaseId"] = source_case["caseId"]

    for field in ("question", "expectedAnswer", "expectedDocuments"):
        if result[field] != source_case[field]:
            raise RuntimeError(f"Case copy changed {field}: {source_case['caseId']}")
    if result["metadata"].get("recommendedMode") != original_mode:
        raise RuntimeError(f"Case copy changed recommendedMode: {source_case['caseId']}")
    return result


def validate_result(result: dict[str, Any]) -> None:
    cases = result["cases"]
    modes = Counter(case["metadata"].get("recommendedMode") for case in cases)
    if len(cases) != 200 or modes != Counter({"FAST": 100, "DEEP": 100}):
        raise RuntimeError(f"Expected 200 balanced cases, found {dict(modes)}")

    questions = [case["question"] for case in cases]
    if len(questions) != len(set(questions)):
        duplicates = [question for question, count in Counter(questions).items() if count > 1]
        raise RuntimeError(f"Duplicate questions selected: {duplicates}")

    case_ids = [case["caseId"] for case in cases]
    if len(case_ids) != len(set(case_ids)):
        raise RuntimeError("Output caseIds are not unique")

    selectors = result["documentSelectors"]
    unknown = sorted(
        {
            alias
            for case in cases
            for alias in case["expectedDocuments"]
            if alias not in selectors
        }
    )
    if unknown:
        raise RuntimeError(f"Cases reference unknown document selectors: {unknown}")

    base_deep_ids = {
        case["metadata"]["routingSourceCaseId"]
        for case in cases
        if case["metadata"]["recommendedMode"] == "DEEP"
        and case["metadata"]["routingSourceBenchmarkId"]
        == "chinese-enterprise-rag-v1"
    }
    if len(base_deep_ids) != 24:
        raise RuntimeError(
            f"Expected all 24 base cross-document DEEP cases, found {len(base_deep_ids)}"
        )

    invalid_no_answer = [
        case["caseId"]
        for case in cases
        if case["metadata"].get("category") == "no_answer"
        and case["metadata"].get("recommendedMode") != "FAST"
    ]
    if invalid_no_answer:
        raise RuntimeError(
            f"Single-intent no-answer cases must route to FAST: {invalid_no_answer}"
        )


def build() -> dict[str, Any]:
    base = load_json(BASE_BLUEPRINT)
    agentic = load_json(AGENTIC_BLUEPRINT)
    if base.get("schemaVersion") != agentic.get("schemaVersion"):
        raise RuntimeError("Source blueprint schema versions do not match")
    if base.get("knowledgeBase") != agentic.get("knowledgeBase"):
        raise RuntimeError("Source blueprints target different knowledge bases")

    fast_source_cases = select_fast(base)
    deep_source_cases = select_deep(base, agentic)
    fast_cases = [
        copy_case(base["benchmarkId"], case, f"AFR-{index:03d}")
        for index, case in enumerate(fast_source_cases, 1)
    ]
    deep_cases = [
        copy_case(benchmark_id, case, f"ADR-{index:03d}")
        for index, (benchmark_id, case) in enumerate(deep_source_cases, 1)
    ]
    cases = fast_cases + deep_cases
    categories = sorted({case["metadata"]["category"] for case in cases})

    result = {
        "schemaVersion": base["schemaVersion"],
        "benchmarkId": "chinese-enterprise-auto-routing-v1",
        "name": "中文企业技术知识库 AUTO 路由平衡集 v1",
        "description": (
            "200条用于评估AUTO问题路由的平衡样本：100条FAST与100条DEEP。"
            "FAST按来源、类别和格式分层，单意图拒答题统一归入FAST；"
            "DEEP保留基础集24条跨文档题，并从Agentic困难集分层补充76条。"
        ),
        "knowledgeBase": deepcopy(base["knowledgeBase"]),
        "expectations": {
            "caseCount": 200,
            "categories": categories,
            "recommendedModes": {"FAST": 100, "DEEP": 100},
            "deepSources": {"baseCrossDocument": 24, "agenticHard": 76},
        },
        "documentSelectors": merge_document_selectors(base, agentic),
        "cases": cases,
    }
    validate_result(result)
    return result


def serialize(result: dict[str, Any]) -> str:
    return json.dumps(result, ensure_ascii=False, indent=2) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if the existing output is missing or differs from a fresh build",
    )
    args = parser.parse_args()

    result = build()
    rendered = serialize(result)
    if args.check:
        if not args.output.is_file():
            raise SystemExit(f"Output does not exist: {args.output}")
        if args.output.read_text(encoding="utf-8") != rendered:
            raise SystemExit(f"Output is stale: {args.output}")
        print(f"Verified {len(result['cases'])} balanced cases in {args.output}")
        return

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"Wrote {len(result['cases'])} balanced cases to {args.output}")


if __name__ == "__main__":
    main()
