#!/usr/bin/env python3
"""Validate and render an Agentic RAG v2 Evaluation API response.

The input is the JSON returned by ``GET /api/v1/evaluation/runs/{runId}``.
The renderer is intentionally offline: the report can always be reproduced
from the persisted API response without querying a mutable database.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean
from typing import Any, Iterable, Mapping, Sequence


EXPECTED_PIPELINE_VERSION = "agentic-hybrid-v2"
EXPECTED_PROMPT_VERSION = "rewrite-v1+planner-v3+evidence-v2+coverage-v3+gap-v3"
ROUTE_MODES = ("FAST", "DEEP")


class ReportValidationError(ValueError):
    """Raised when a persisted run is not suitable for an official report."""

    def __init__(self, problems: Sequence[str]):
        super().__init__("\n".join(problems))
        self.problems = list(problems)


@dataclass(frozen=True)
class RuntimeAudit:
    deep_rows: int
    audited_rows: int
    missing_snapshots: int
    pipeline_versions: Counter[str]
    prompt_versions: Counter[str]
    chat_profiles: Counter[str]
    chat_models: Counter[str]
    rewrite_models: Counter[str]
    rerank_models: Counter[str]


@dataclass(frozen=True)
class ToolHealthAudit:
    successful_rows: int
    deep_rows: int
    tool_failure_rows: int
    tool_failure_count: int
    deep_read_failure_rows: int
    deep_read_failure_count: int
    missing_judge_rows: int
    evidence_judge_failure_rows: int
    evidence_judge_failure_count: int

    @property
    def healthy(self) -> bool:
        return not any((
            self.tool_failure_rows,
            self.deep_read_failure_rows,
            self.missing_judge_rows,
            self.evidence_judge_failure_rows,
        ))


@dataclass(frozen=True)
class RoutingSummary:
    graded: int
    correct: int
    confusion: Mapping[str, Counter[str]]
    by_source: Mapping[str, Mapping[str, Any]]

    @property
    def accuracy(self) -> float | None:
        return self.correct / self.graded if self.graded else None


def mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def metric(row: Mapping[str, Any]) -> Mapping[str, Any]:
    return mapping(row.get("metrics"))


def numeric(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def number(value: Any, default: float = 0.0) -> float:
    parsed = numeric(value)
    return default if parsed is None else parsed


def integer(value: Any, default: int = 0) -> int:
    parsed = numeric(value)
    return default if parsed is None else int(parsed)


def nonempty(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def row_failed(row: Mapping[str, Any]) -> bool:
    return bool(nonempty(row.get("errorMessage")))


def deep_row(row: Mapping[str, Any], requested_mode: str) -> bool:
    selected = nonempty(metric(row).get("selectedMode")).upper()
    return selected == "DEEP" or (not selected and requested_mode == "DEEP")


def diagnostic_count(values: Mapping[str, Any], *keys: str) -> int:
    diagnostics = mapping(values.get("toolDiagnostics"))
    return max(
        [0, *(
            max(0, integer(source.get(key)))
            for source in (values, diagnostics)
            for key in keys
        )]
    )


def tool_count(values: Mapping[str, Any], tool: str, key: str) -> int:
    diagnostics = mapping(values.get("toolDiagnostics"))
    return max(0, integer(mapping(diagnostics.get(f"tool.{tool}")).get(key)))


def evidence_judge_calls(values: Mapping[str, Any]) -> int:
    return max(
        diagnostic_count(values, "judgeCallCount"),
        tool_count(values, "evidence_judge", "calls"),
    )


def tool_health_audit(
        rows: Sequence[Mapping[str, Any]], execution: str, requested_mode: str
) -> tuple[ToolHealthAudit, list[str]]:
    if execution != "RAG":
        return ToolHealthAudit(0, 0, 0, 0, 0, 0, 0, 0, 0), []

    successful = [row for row in rows if not row_failed(row)]
    deep = [row for row in successful if deep_row(row, requested_mode)]
    tool_failures: list[tuple[str, int]] = []
    deep_read_failures: list[tuple[str, int]] = []
    missing_judges: list[str] = []
    judge_failures: list[tuple[str, int]] = []
    for row in successful:
        values = metric(row)
        case_id = nonempty(row.get("evaluationCaseId")) or "MISSING"
        total_failures = max(0, integer(values.get("toolFailureCount")))
        if total_failures:
            tool_failures.append((case_id, total_failures))
        deep_failures = max(
            diagnostic_count(values, "deepReadFailureCount"),
            tool_count(values, "deep_read", "failed"),
        )
        if deep_failures:
            deep_read_failures.append((case_id, deep_failures))
        if deep_row(row, requested_mode) and evidence_judge_calls(values) < 1:
            missing_judges.append(case_id)
        judge_failed = max(
            diagnostic_count(values, "evidenceJudgeFailureCount"),
            tool_count(values, "evidence_judge", "failed"),
        )
        if judge_failed:
            judge_failures.append((case_id, judge_failed))

    audit = ToolHealthAudit(
        successful_rows=len(successful),
        deep_rows=len(deep),
        tool_failure_rows=len(tool_failures),
        tool_failure_count=sum(count for _, count in tool_failures),
        deep_read_failure_rows=len(deep_read_failures),
        deep_read_failure_count=sum(count for _, count in deep_read_failures),
        missing_judge_rows=len(missing_judges),
        evidence_judge_failure_rows=len(judge_failures),
        evidence_judge_failure_count=sum(count for _, count in judge_failures),
    )
    problems: list[str] = []
    if tool_failures:
        problems.append(
            "Successful RAG rows report toolFailureCount > 0: "
            f"{len(tool_failures)} rows, {audit.tool_failure_count} failures "
            f"(first: {tool_failures[0][0]})")
    if deep_read_failures:
        problems.append(
            "Successful RAG rows report deepReadFailureCount > 0: "
            f"{len(deep_read_failures)} rows, {audit.deep_read_failure_count} failures "
            f"(first: {deep_read_failures[0][0]})")
    if missing_judges:
        problems.append(
            "Successful DEEP rows missing a mandatory Evidence Judge call: "
            f"{len(missing_judges)} (first: {missing_judges[0]})")
    if judge_failures:
        problems.append(
            "Successful RAG rows report tool.evidence_judge.failed > 0: "
            f"{len(judge_failures)} rows, {audit.evidence_judge_failure_count} failures "
            f"(first: {judge_failures[0][0]})")
    return audit, problems


def row_values(rows: Iterable[Mapping[str, Any]], key: str) -> list[float]:
    values: list[float] = []
    for row in rows:
        value = numeric(metric(row).get(key))
        if value is not None:
            values.append(value)
    return values


def row_sum(rows: Iterable[Mapping[str, Any]], key: str) -> int:
    return int(sum(row_values(rows, key)))


def row_average(rows: Iterable[Mapping[str, Any]], key: str) -> float | None:
    values = row_values(rows, key)
    return mean(values) if values else None


def percentile(values: Iterable[float], quantile: float) -> float | None:
    ordered = sorted(values)
    if not ordered:
        return None
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * quantile) - 1))
    return ordered[index]


def fmt_rate(value: Any) -> str:
    parsed = numeric(value)
    return "n/a" if parsed is None else f"{parsed:.4f}"


def fmt_float(value: Any, digits: int = 2) -> str:
    parsed = numeric(value)
    return "n/a" if parsed is None else f"{parsed:.{digits}f}"


def fmt_count(value: Any) -> str:
    parsed = numeric(value)
    return "n/a" if parsed is None else f"{int(parsed)}"


def fmt_ms(value: Any) -> str:
    parsed = numeric(value)
    return "n/a" if parsed is None else f"{parsed:.2f} ms"


def markdown(value: Any, limit: int | None = None) -> str:
    text = nonempty(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ")
    if limit is not None and len(text) > limit:
        text = text[: max(0, limit - 3)] + "..."
    return text or "n/a"


def model_descriptor(snapshot: Mapping[str, Any], key: str, profile_key: str) -> str:
    model = mapping(snapshot.get(key))
    profile_id = nonempty(model.get("id")) or nonempty(snapshot.get(profile_key))
    if not model and not profile_id:
        return ""
    parts = [
        f"id={profile_id or 'unknown'}",
        f"provider={nonempty(model.get('provider')) or 'unknown'}",
        f"model={nonempty(model.get('modelName')) or 'unknown'}",
        f"name={nonempty(model.get('name')) or 'unknown'}",
        f"revision={nonempty(model.get('revision')) or 'unknown'}",
    ]
    settings_hash = nonempty(model.get("settingsSha256"))
    if settings_hash:
        parts.append(f"settings={settings_hash}")
    return "; ".join(parts)


def runtime_audit(
        rows: Sequence[Mapping[str, Any]], execution: str, requested_mode: str
) -> RuntimeAudit:
    if execution == "ROUTING_ONLY":
        return RuntimeAudit(0, 0, 0, Counter(), Counter(), Counter(), Counter(), Counter(), Counter())

    deep_rows: list[Mapping[str, Any]] = []
    for row in rows:
        if deep_row(row, requested_mode):
            deep_rows.append(row)

    pipelines: Counter[str] = Counter()
    prompts: Counter[str] = Counter()
    profiles: Counter[str] = Counter()
    chat_models: Counter[str] = Counter()
    rewrite_models: Counter[str] = Counter()
    rerank_models: Counter[str] = Counter()
    missing = 0
    audited = 0
    for row in deep_rows:
        snapshot = mapping(metric(row).get("runtimeSnapshot"))
        if not snapshot:
            # A failed case may end before a runtime is attached; the operational
            # failure remains visible without creating a second validation error.
            if not row_failed(row):
                missing += 1
            continue
        audited += 1
        pipelines[nonempty(snapshot.get("pipelineVersion")) or "MISSING"] += 1
        prompts[nonempty(snapshot.get("promptVersion")) or "MISSING"] += 1
        profile_id = nonempty(snapshot.get("chatProfileId"))
        if not profile_id:
            profile_id = nonempty(mapping(snapshot.get("chatModel")).get("id"))
        profiles[profile_id or "MISSING"] += 1
        chat_models[model_descriptor(snapshot, "chatModel", "chatProfileId") or "MISSING"] += 1
        rewrite_models[model_descriptor(
            snapshot, "queryRewriteModel", "queryRewriteProfileId") or "MISSING"] += 1
        rerank_models[model_descriptor(snapshot, "rerankModel", "rerankProfileId") or "MISSING"] += 1
    return RuntimeAudit(
        len(deep_rows), audited, missing, pipelines, prompts, profiles,
        chat_models, rewrite_models, rerank_models)


def routing_summary(rows: Sequence[Mapping[str, Any]], execution: str) -> RoutingSummary:
    graded_rows: list[tuple[str, str, str, float | None]] = []
    confusion: dict[str, Counter[str]] = {mode: Counter() for mode in ROUTE_MODES}
    for row in rows:
        values = metric(row)
        expected = nonempty(values.get("expectedMode")).upper()
        if expected not in ROUTE_MODES:
            continue
        selected = nonempty(values.get("selectedMode")).upper() or "UNKNOWN"
        source = nonempty(values.get("routeDecisionSource")).upper() or "UNKNOWN"
        latency_key = "latencyMs" if execution == "ROUTING_ONLY" else "routeLatencyMs"
        latency = numeric(values.get(latency_key))
        confusion[expected][selected] += 1
        graded_rows.append((expected, selected, source, latency))

    source_groups: dict[str, list[tuple[str, str, float | None]]] = defaultdict(list)
    for expected, selected, source, latency in graded_rows:
        source_groups[source].append((expected, selected, latency))
    by_source: dict[str, Mapping[str, Any]] = {}
    for source, values in sorted(source_groups.items()):
        correct = sum(expected == selected for expected, selected, _ in values)
        latencies = [latency for _, _, latency in values if latency is not None]
        by_source[source] = {
            "cases": len(values),
            "correct": correct,
            "accuracy": correct / len(values) if values else None,
            "expectedFast": sum(expected == "FAST" for expected, _, _ in values),
            "expectedDeep": sum(expected == "DEEP" for expected, _, _ in values),
            "selectedFast": sum(selected == "FAST" for _, selected, _ in values),
            "selectedDeep": sum(selected == "DEEP" for _, selected, _ in values),
            "averageLatencyMs": mean(latencies) if latencies else None,
            "p95LatencyMs": percentile(latencies, 0.95),
        }
    correct = sum(expected == selected for expected, selected, _, _ in graded_rows)
    return RoutingSummary(len(graded_rows), correct, confusion, by_source)


def load_blueprint(path: Path | None) -> tuple[dict[str, Mapping[str, Any]], list[str]]:
    if path is None:
        return {}, []
    if not path.exists():
        return {}, [f"Blueprint not found; grouped metadata may be incomplete: {path}"]
    payload = json.loads(path.read_text(encoding="utf-8"))
    cases = payload.get("cases") if isinstance(payload, Mapping) else None
    if not isinstance(cases, list):
        raise ReportValidationError([f"Blueprint has no cases array: {path}"])
    metadata: dict[str, Mapping[str, Any]] = {}
    duplicate_questions: list[str] = []
    for case in cases:
        if not isinstance(case, Mapping):
            continue
        question = nonempty(case.get("question"))
        if not question:
            continue
        if question in metadata:
            duplicate_questions.append(question)
        metadata[question] = mapping(case.get("metadata"))
    if duplicate_questions:
        raise ReportValidationError([
            f"Blueprint contains duplicate questions ({len(duplicate_questions)}): "
            f"{duplicate_questions[0]}"
        ])
    return metadata, []


def metadata_for(
        row: Mapping[str, Any], metadata_by_question: Mapping[str, Mapping[str, Any]]
) -> dict[str, Any]:
    result = dict(metadata_by_question.get(nonempty(row.get("question")), {}))
    values = metric(row)
    for key in ("challengeType", "sourceProject", "intentCount", "recommendedMode", "category"):
        if values.get(key) is not None:
            result[key] = values[key]
    return result


def validate_run(
        run: Mapping[str, Any],
        rows: Sequence[Mapping[str, Any]],
        expected_rows: int,
        allow_failures: bool,
        allow_incomplete: bool,
        allow_mixed_runtime: bool,
        allow_tool_failures: bool,
        expected_pipeline: str,
        expected_prompt: str,
        expected_model_profile_id: str | None,
) -> tuple[list[str], RuntimeAudit, ToolHealthAudit]:
    problems: list[str] = []
    warnings: list[str] = []
    aggregate = mapping(run.get("aggregateMetrics"))
    execution = nonempty(aggregate.get("execution")).upper()
    requested_mode = nonempty(aggregate.get("requestedMode")).upper()
    status = nonempty(run.get("status")).upper()

    if execution not in {"RAG", "ROUTING_ONLY"}:
        problems.append(
            f"Unsupported execution {execution or 'MISSING'}; expected RAG or ROUTING_ONLY")
    if execution == "RAG" and requested_mode not in {"DEEP", "AUTO"}:
        problems.append(
            f"Unsupported RAG requestedMode {requested_mode or 'MISSING'}; expected DEEP or AUTO")
    if execution == "ROUTING_ONLY" and requested_mode != "AUTO":
        problems.append("ROUTING_ONLY run must have requestedMode=AUTO")
    if status != "COMPLETED":
        message = f"Run status is {status or 'MISSING'}, not COMPLETED"
        (warnings if allow_incomplete else problems).append(message)
    if expected_rows > 0 and len(rows) != expected_rows:
        problems.append(f"Expected {expected_rows} result rows, found {len(rows)}")

    case_ids = [nonempty(row.get("evaluationCaseId")) for row in rows]
    missing_case_ids = sum(not value for value in case_ids)
    duplicate_case_ids = [value for value, count in Counter(case_ids).items() if value and count > 1]
    if missing_case_ids:
        problems.append(f"Result rows missing evaluationCaseId: {missing_case_ids}")
    if duplicate_case_ids:
        problems.append(
            f"Duplicate evaluationCaseId values: {len(duplicate_case_ids)} "
            f"(first: {duplicate_case_ids[0]})")

    questions = [nonempty(row.get("question")) for row in rows]
    missing_questions = sum(not value for value in questions)
    duplicate_questions = [value for value, count in Counter(questions).items() if value and count > 1]
    if missing_questions:
        problems.append(f"Result rows missing question text: {missing_questions}")
    if duplicate_questions:
        problems.append(
            f"Duplicate question texts: {len(duplicate_questions)} "
            f"(first: {duplicate_questions[0]})")
    invalid_metrics = sum(not isinstance(row.get("metrics"), Mapping) for row in rows)
    if invalid_metrics:
        problems.append(f"Result rows with missing/non-object metrics: {invalid_metrics}")

    failed = sum(row_failed(row) for row in rows)
    aggregate_failed = integer(aggregate.get("failedCases"), failed)
    aggregate_success = integer(aggregate.get("successfulCases"), len(rows) - failed)
    aggregate_cases = integer(aggregate.get("caseCount"), len(rows))
    if aggregate_cases != len(rows):
        problems.append(
            f"aggregateMetrics.caseCount={aggregate_cases} does not match {len(rows)} result rows")
    if aggregate_failed != failed:
        problems.append(
            f"aggregateMetrics.failedCases={aggregate_failed} does not match {failed} failed rows")
    if aggregate_success + aggregate_failed != aggregate_cases:
        problems.append(
            "aggregateMetrics successfulCases + failedCases does not equal caseCount")
    if failed:
        message = f"Run contains {failed} failed result rows"
        (warnings if allow_failures else problems).append(message)

    tool_audit, tool_problems = tool_health_audit(rows, execution, requested_mode)
    if tool_problems:
        target = warnings if allow_tool_failures else problems
        target.extend(
            f"Tool health gate waived: {problem}" if allow_tool_failures else problem
            for problem in tool_problems
        )

    audit = runtime_audit(rows, execution, requested_mode)
    if execution == "RAG":
        if audit.missing_snapshots:
            problems.append(
                f"Successful DEEP rows missing runtimeSnapshot: {audit.missing_snapshots}")
        wrong_pipelines = {
            value: count for value, count in audit.pipeline_versions.items()
            if value != expected_pipeline
        }
        if wrong_pipelines:
            message = (
                f"DEEP rows do not all use pipelineVersion={expected_pipeline}: {wrong_pipelines}")
            (warnings if allow_mixed_runtime else problems).append(message)
        wrong_prompts = {
            value: count for value, count in audit.prompt_versions.items()
            if expected_prompt and value != expected_prompt
        }
        if wrong_prompts:
            message = (
                f"DEEP rows do not all use promptVersion={expected_prompt}: {wrong_prompts}")
            (warnings if allow_mixed_runtime else problems).append(message)

        mixed_fields = {
            "pipeline versions": audit.pipeline_versions,
            "prompt versions": audit.prompt_versions,
            "chat profile IDs": audit.chat_profiles,
            "chat model snapshots": audit.chat_models,
            "query rewrite model snapshots": audit.rewrite_models,
            "rerank model snapshots": audit.rerank_models,
        }
        for label, values in mixed_fields.items():
            if len(values) > 1:
                message = f"Mixed {label} across DEEP rows: {dict(values)}"
                (warnings if allow_mixed_runtime else problems).append(message)

        aggregate_profile = nonempty(aggregate.get("modelProfileId"))
        expected_profile = nonempty(expected_model_profile_id) or aggregate_profile
        if expected_profile:
            mismatches = {
                profile: count for profile, count in audit.chat_profiles.items()
                if profile != expected_profile
            }
            if mismatches:
                message = (
                    f"DEEP runtime chat profile does not match {expected_profile}: {mismatches}")
                (warnings if allow_mixed_runtime else problems).append(message)
        if audit.deep_rows and not audit.audited_rows:
            problems.append("No DEEP result row contains an auditable runtimeSnapshot")

    if problems:
        raise ReportValidationError(problems)
    return warnings, audit, tool_audit


def default_blueprint(execution: str) -> Path:
    if execution == "ROUTING_ONLY":
        return Path("benchmarks/chinese-enterprise-auto-routing-v1.blueprint.json")
    return Path("benchmarks/chinese-enterprise-agentic-retrieval-v1.blueprint.json")


def counter_table(title: str, counter: Counter[str]) -> list[str]:
    lines = [f"### {title}", "", "| Group | Failures |", "| --- | ---: |"]
    if not counter:
        lines.append("| None | 0 |")
    else:
        for value, count in sorted(counter.items(), key=lambda item: (-item[1], item[0])):
            lines.append(f"| {markdown(value)} | {count} |")
    lines.append("")
    return lines


def runtime_counter_lines(label: str, values: Counter[str]) -> list[str]:
    if not values:
        return [f"- {label}: `n/a`"]
    return [f"- {label}: `{markdown(value)}` ({count} rows)" for value, count in values.items()]


def render_report(
        run: Mapping[str, Any],
        rows: Sequence[Mapping[str, Any]],
        metadata_by_question: Mapping[str, Mapping[str, Any]],
        warnings: Sequence[str],
        audit: RuntimeAudit,
        tool_audit: ToolHealthAudit,
        blueprint_path: Path | None,
) -> str:
    aggregate = mapping(run.get("aggregateMetrics"))
    execution = nonempty(aggregate.get("execution")).upper()
    requested_mode = nonempty(aggregate.get("requestedMode")).upper()
    successful = [row for row in rows if not row_failed(row)]
    failed = [row for row in rows if row_failed(row)]
    routing = routing_summary(rows, execution)
    deep_successful = [
        row for row in successful
        if deep_row(row, requested_mode)
    ]
    judge_compliant = sum(evidence_judge_calls(metric(row)) >= 1 for row in deep_successful)
    judge_compliance = judge_compliant / len(deep_successful) if deep_successful else None

    lines = [
        "# Agentic RAG v2 Evaluation Report",
        "",
        "## Run snapshot",
        "",
        f"- Evaluation run: `{nonempty(run.get('id')) or 'n/a'}`",
        f"- Dataset: `{nonempty(run.get('datasetId')) or 'n/a'}`",
        f"- Status: **{nonempty(run.get('status')) or 'n/a'}**",
        f"- Execution: `{execution}`; requested mode: `{requested_mode}`; judge mode: `{nonempty(aggregate.get('judgeMode')) or 'n/a'}`",
        f"- Results: **{len(rows)}** unique rows; successful: **{len(successful)}**; failed: **{len(failed)}**",
        f"- Scope: knowledge bases `{aggregate.get('knowledgeBaseIds', [])}`; documents `{aggregate.get('documentIds', [])}`; metadata filters `{integer(aggregate.get('metadataFilterCount'))}`",
        f"- Requested model profile: `{nonempty(aggregate.get('modelProfileId')) or 'n/a'}`; case parallelism: `{fmt_count(aggregate.get('caseParallelism'))}`",
        f"- Started: `{nonempty(run.get('startedAt')) or 'n/a'}`; completed: `{nonempty(run.get('completedAt')) or 'n/a'}`",
        f"- Metadata blueprint: `{blueprint_path if blueprint_path else 'not supplied'}`",
        "",
        "## Validation and runtime audit",
        "",
        "- Structural validation: **PASS** (cardinality, unique case IDs, unique questions, and aggregate consistency).",
        f"- Completion/failure gate: **{'PASS' if not failed else 'WAIVED'}**.",
    ]
    if execution == "ROUTING_ONLY":
        lines.append("- DEEP pipeline audit: `not applicable` for routing-only execution.")
        lines.append("- Tool health gate: `not applicable` for routing-only execution.")
    else:
        lines.extend([
            f"- DEEP rows: `{audit.deep_rows}`; runtime snapshots audited: `{audit.audited_rows}`; missing on successful rows: `{audit.missing_snapshots}`.",
            f"- Tool health gate: **{'PASS' if tool_audit.healthy else 'WAIVED'}**; "
            f"successful rows audited: `{tool_audit.successful_rows}`; DEEP rows audited: `{tool_audit.deep_rows}`.",
            f"- Tool degradation: tool failures `{tool_audit.tool_failure_count}` across "
            f"`{tool_audit.tool_failure_rows}` rows; deep-read failures "
            f"`{tool_audit.deep_read_failure_count}` across `{tool_audit.deep_read_failure_rows}` rows; "
            f"DEEP rows missing Evidence Judge `{tool_audit.missing_judge_rows}`; Evidence Judge failures "
            f"`{tool_audit.evidence_judge_failure_count}` across "
            f"`{tool_audit.evidence_judge_failure_rows}` rows.",
        ])
        lines.extend(runtime_counter_lines("Pipeline version", audit.pipeline_versions))
        lines.extend(runtime_counter_lines("Prompt version", audit.prompt_versions))
        lines.extend(runtime_counter_lines("Chat profile ID", audit.chat_profiles))
        lines.extend(runtime_counter_lines("Chat model snapshot", audit.chat_models))
        lines.extend(runtime_counter_lines("Query rewrite model snapshot", audit.rewrite_models))
        lines.extend(runtime_counter_lines("Rerank model snapshot", audit.rerank_models))
    if warnings:
        lines.extend(["", "### Warnings", ""])
        lines.extend(f"- {markdown(value)}" for value in warnings)

    lines.extend([
        "",
        "## Retrieval quality",
        "",
        "| Metric | Value | Graded cases |",
        "| --- | ---: | ---: |",
        f"| Recall@5 | {fmt_rate(aggregate.get('recallAt5'))} | {fmt_count(aggregate.get('retrievalGradedCases'))} |",
        f"| Recall@10 | {fmt_rate(aggregate.get('recallAt10'))} | {fmt_count(aggregate.get('retrievalGradedCases'))} |",
        f"| Precision@5 / @10 | {fmt_rate(aggregate.get('precisionAt5'))} / {fmt_rate(aggregate.get('precisionAt10'))} | {fmt_count(aggregate.get('retrievalGradedCases'))} |",
        f"| Hit@5 / @10 | {fmt_rate(aggregate.get('hitAt5'))} / {fmt_rate(aggregate.get('hitAt10'))} | {fmt_count(aggregate.get('retrievalGradedCases'))} |",
        f"| MRR@5 / MRR | {fmt_rate(aggregate.get('mrrAt5'))} / {fmt_rate(aggregate.get('mrr'))} | {fmt_count(aggregate.get('retrievalGradedCases'))} |",
        f"| nDCG@5 / @10 | {fmt_rate(aggregate.get('ndcgAt5'))} / {fmt_rate(aggregate.get('ndcgAt10'))} | {fmt_count(aggregate.get('retrievalGradedCases'))} |",
        f"| MAP@5 / @10 | {fmt_rate(aggregate.get('mapAt5'))} / {fmt_rate(aggregate.get('mapAt10'))} | {fmt_count(aggregate.get('retrievalGradedCases'))} |",
        f"| Expected-answer coverage | {fmt_rate(aggregate.get('expectedAnswerCoverage'))} | {fmt_count(aggregate.get('answerGradedCases'))} |",
        "",
        "## Answer and citation quality",
        "",
        "| Metric | Value | Graded cases |",
        "| --- | ---: | ---: |",
        f"| RAG answers executed | {fmt_count(aggregate.get('ragExecutedCases'))} | {fmt_count(aggregate.get('caseCount'))} |",
        f"| Semantic answer score | {fmt_rate(aggregate.get('semanticAnswerScore'))} | {fmt_count(aggregate.get('semanticAnswerJudgedCases'))} |",
        f"| No-answer accuracy | {fmt_rate(aggregate.get('noAnswerAccuracy'))} | {fmt_count(aggregate.get('noAnswerGradedCases'))} |",
        f"| Citation resolvable rate | {fmt_rate(aggregate.get('citationResolvableRate'))} | {fmt_count(aggregate.get('citationGradedCases'))} |",
        f"| Citation entailment score | {fmt_rate(aggregate.get('citationEntailmentScore'))} | {fmt_count(aggregate.get('citationEntailmentJudgedCases'))} |",
        f"| Evaluation judge failures | {fmt_count(aggregate.get('judgeFailedCases'))} | {fmt_count(aggregate.get('ragExecutedCases'))} |",
        f"| Effective-version leaks | {fmt_count(aggregate.get('effectiveVersionLeakCount'))} | {fmt_count(aggregate.get('ragExecutedCases'))} |",
        f"| Scope leaks | {fmt_count(aggregate.get('scopeLeakCount'))} | {fmt_count(aggregate.get('ragExecutedCases'))} |",
        "",
        "## Evidence loop and tools",
        "",
        "| Metric | Value |",
        "| --- | ---: |",
        f"| Retrieval tasks | {row_sum(successful, 'retrievalTaskCount')} |",
        f"| Rerank-skipped tasks | {row_sum(successful, 'rerankSkippedCount')} |",
        f"| Evidence items | {row_sum(successful, 'evidenceCount')} total / {fmt_float(row_average(successful, 'evidenceCount'))} average |",
        f"| Evidence Judge calls | {row_sum(successful, 'judgeCallCount')} |",
        f"| Evidence Judge sufficient decisions | {row_sum(successful, 'judgeSufficientCount')} |",
        f"| Evidence Judge call compliance on DEEP cases | {fmt_rate(judge_compliance)} ({judge_compliant}/{len(deep_successful)}) |",
        f"| Gap queries | {row_sum(successful, 'gapQueryCount')} |",
        "| Deep-read diagnostic semantics | `evidence extraction outcomes; not physical parent-block read count` |",
        f"| Deep-read compliance | {fmt_rate(aggregate.get('deepReadComplianceRate'))} |",
        f"| Deep-read expected-document recall | {fmt_rate(aggregate.get('deepReadRecall'))} |",
        f"| All-tool expected-document recall | {fmt_rate(aggregate.get('allToolCoverageRecall'))} |",
        f"| Strict discovery Recall@5 / @10 | {fmt_rate(aggregate.get('strictDiscoveryRecallAt5'))} / {fmt_rate(aggregate.get('strictDiscoveryRecallAt10'))} |",
        f"| Tool calls | {fmt_count(aggregate.get('toolCallCount'))} total / {fmt_float(aggregate.get('averageToolCalls'))} average |",
        f"| Tool failures | {fmt_count(aggregate.get('toolFailureCount'))} ({fmt_rate(aggregate.get('toolFailureRate'))}) |",
        f"| Budget rejections | {fmt_count(aggregate.get('budgetRejectionCount'))} |",
        f"| Average iterations | {fmt_float(aggregate.get('averageIterations'))} |",
        f"| Context compressions | {fmt_count(aggregate.get('contextCompressionCount'))} |",
        f"| Tokens (input / output / total) | {fmt_count(aggregate.get('inputTokens'))} / {fmt_count(aggregate.get('outputTokens'))} / {fmt_count(aggregate.get('totalTokens'))} |",
        "",
        "## Latency",
        "",
        "| Mean | P50 | P95 | P99 |",
        "| ---: | ---: | ---: | ---: |",
        f"| {fmt_ms(aggregate.get('averageLatencyMs'))} | {fmt_ms(aggregate.get('p50LatencyMs'))} | {fmt_ms(aggregate.get('p95LatencyMs'))} | {fmt_ms(aggregate.get('p99LatencyMs'))} |",
        "",
        "## Routing",
        "",
        f"- Independently calculated graded cases: `{routing.graded}`; correct: `{routing.correct}`; accuracy: **{fmt_rate(routing.accuracy)}**.",
        f"- API aggregate accuracy: `{fmt_rate(aggregate.get('routingAccuracy'))}`; classifier attempts: `{fmt_count(aggregate.get('classifierAttemptCount'))}`; classifier success rate: `{fmt_rate(aggregate.get('classifierSuccessRate'))}`.",
        f"- Router fallbacks: `{fmt_count(aggregate.get('routerFallbackCount'))}`; fallback rate: `{fmt_rate(aggregate.get('routerFallbackRate'))}`.",
    ])
    for source in ("LLM", "HEURISTIC", "FALLBACK"):
        source_values = routing.by_source.get(source)
        if source_values is not None:
            lines.append(
                f"- {source}-only routing accuracy: `{fmt_rate(source_values['accuracy'])}` "
                f"({source_values['correct']}/{source_values['cases']}).")
    lines.extend([
        "",
        "### Confusion matrix (calculated from case rows)",
        "",
        "| Expected \\ Selected | FAST | DEEP | ERROR | UNKNOWN | Other |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ])
    for expected in ROUTE_MODES:
        counts = routing.confusion.get(expected, Counter())
        other = sum(count for selected, count in counts.items()
                    if selected not in {"FAST", "DEEP", "ERROR", "UNKNOWN"})
        lines.append(
            f"| {expected} | {counts['FAST']} | {counts['DEEP']} | "
            f"{counts['ERROR']} | {counts['UNKNOWN']} | {other} |")

    lines.extend([
        "",
        "### Accuracy by decision source",
        "",
        "| Decision source | Cases | Correct | Accuracy | Expected FAST | Expected DEEP | Selected FAST | Selected DEEP | Mean route latency | P95 route latency |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ])
    if not routing.by_source:
        lines.append("| n/a | 0 | 0 | n/a | 0 | 0 | 0 | 0 | n/a | n/a |")
    else:
        for source, values in routing.by_source.items():
            lines.append(
                f"| {markdown(source)} | {values['cases']} | {values['correct']} | "
                f"{fmt_rate(values['accuracy'])} | {values['expectedFast']} | {values['expectedDeep']} | "
                f"{values['selectedFast']} | {values['selectedDeep']} | "
                f"{fmt_ms(values['averageLatencyMs'])} | {fmt_ms(values['p95LatencyMs'])} |")

    phase_failures: Counter[str] = Counter()
    challenge_failures: Counter[str] = Counter()
    source_failures: Counter[str] = Counter()
    decision_source_failures: Counter[str] = Counter()
    for row in failed:
        values = metric(row)
        metadata = metadata_for(row, metadata_by_question)
        phase = nonempty(values.get("failurePhase"))
        if not phase and execution == "ROUTING_ONLY":
            phase = "ROUTING"
        phase_failures[phase or "UNKNOWN"] += 1
        challenge = nonempty(metadata.get("challengeType")) or nonempty(metadata.get("category"))
        challenge_failures[challenge or "UNKNOWN"] += 1
        source_failures[nonempty(metadata.get("sourceProject")) or "UNKNOWN"] += 1
        decision_source_failures[
            nonempty(values.get("routeDecisionSource")).upper() or "UNKNOWN"] += 1
    lines.extend(["", "## Operational failures", ""])
    lines.extend(counter_table("By failure phase", phase_failures))
    lines.extend(counter_table("By challenge", challenge_failures))
    lines.extend(counter_table("By source project", source_failures))
    lines.extend(counter_table("By routing decision source", decision_source_failures))

    lines.extend([
        "### Failure details",
        "",
        "| # | Case | Phase | Challenge | Source project | Route source | Message |",
        "| ---: | --- | --- | --- | --- | --- | --- |",
    ])
    if not failed:
        lines.append("| - | - | - | - | - | - | No operational failures |")
    else:
        for index, row in enumerate(failed, 1):
            values = metric(row)
            metadata = metadata_for(row, metadata_by_question)
            lines.append(
                f"| {index} | {markdown(row.get('evaluationCaseId'), 40)} | "
                f"{markdown(values.get('failurePhase') or ('ROUTING' if execution == 'ROUTING_ONLY' else 'UNKNOWN'))} | "
                f"{markdown(metadata.get('challengeType') or metadata.get('category'))} | "
                f"{markdown(metadata.get('sourceProject'))} | "
                f"{markdown(values.get('routeDecisionSource'))} | "
                f"{markdown(row.get('errorMessage'), 180)} |")

    lines.extend([
        "",
        "## Per-case audit",
        "",
        "| # | Case | Question | Status | Expected | Selected | Route source | R@5 | R@10 | Evidence | Judge calls | Gap queries | Tool calls | Latency |",
        "| ---: | --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ])
    for index, row in enumerate(rows, 1):
        values = metric(row)
        lines.append(
            f"| {index} | {markdown(row.get('evaluationCaseId'), 40)} | "
            f"{markdown(row.get('question'), 96)} | "
            f"{'FAILED' if row_failed(row) else 'OK'} | "
            f"{markdown(values.get('expectedMode'))} | {markdown(values.get('selectedMode'))} | "
            f"{markdown(values.get('routeDecisionSource'))} | "
            f"{fmt_rate(values.get('recallAt5'))} | {fmt_rate(values.get('recallAt10'))} | "
            f"{fmt_count(values.get('evidenceCount'))} | {fmt_count(values.get('judgeCallCount'))} | "
            f"{fmt_count(values.get('gapQueryCount'))} | {fmt_count(values.get('toolCallCount'))} | "
            f"{fmt_ms(values.get('latencyMs'))} |")
    return "\n".join(lines) + "\n"


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate and render a full DEEP/AUTO or ROUTING_ONLY Evaluation run")
    parser.add_argument(
        "run", type=Path,
        help="JSON returned by GET /api/v1/evaluation/runs/{runId}")
    parser.add_argument("--output", type=Path, required=True, help="Markdown report path")
    parser.add_argument(
        "--blueprint", type=Path,
        help="Optional benchmark blueprint for failure grouping; inferred for standard v1 runs")
    parser.add_argument("--expected-rows", type=int, default=200)
    parser.add_argument("--allow-failures", action="store_true")
    parser.add_argument("--allow-incomplete", action="store_true")
    parser.add_argument(
        "--allow-tool-failures", action="store_true",
        help=("Render degraded successful RAG rows with warnings instead of failing when tools "
              "failed or a DEEP row omitted the mandatory Evidence Judge call"))
    parser.add_argument(
        "--allow-mixed-runtime", action="store_true",
        help="Render with warnings if DEEP runtime snapshots are mixed or differ from expected values")
    parser.add_argument("--expected-pipeline-version", default=EXPECTED_PIPELINE_VERSION)
    parser.add_argument("--expected-prompt-version", default=EXPECTED_PROMPT_VERSION)
    parser.add_argument("--expected-model-profile-id")
    args = parser.parse_args(argv)
    if args.expected_rows < 0:
        parser.error("--expected-rows must be zero or greater")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        payload = json.loads(args.run.read_text(encoding="utf-8"))
        if not isinstance(payload, Mapping):
            raise ReportValidationError(["Evaluation response must be a JSON object"])
        run = mapping(payload.get("run"))
        results = payload.get("results")
        if not run:
            raise ReportValidationError(["Evaluation response has no run object"])
        if not isinstance(results, list) or any(not isinstance(row, Mapping) for row in results):
            raise ReportValidationError(["Evaluation response has no valid results array"])
        rows: list[Mapping[str, Any]] = list(results)
        aggregate = mapping(run.get("aggregateMetrics"))
        execution = nonempty(aggregate.get("execution")).upper()
        blueprint_path = args.blueprint or default_blueprint(execution)
        metadata_by_question, blueprint_warnings = load_blueprint(blueprint_path)
        warnings, audit, tool_audit = validate_run(
            run, rows, args.expected_rows, args.allow_failures, args.allow_incomplete,
            args.allow_mixed_runtime, args.allow_tool_failures, args.expected_pipeline_version,
            args.expected_prompt_version, args.expected_model_profile_id)
        report = render_report(
            run, rows, metadata_by_question, [*blueprint_warnings, *warnings], audit, tool_audit,
            blueprint_path)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
        print(f"Wrote validated {execution} report with {len(rows)} rows to {args.output}")
        return 0
    except (OSError, json.JSONDecodeError, ReportValidationError) as error:
        if isinstance(error, ReportValidationError):
            print("Report validation failed:", file=sys.stderr)
            for problem in error.problems:
                print(f"- {problem}", file=sys.stderr)
        else:
            print(f"Unable to render report: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
