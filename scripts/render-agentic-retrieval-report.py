#!/usr/bin/env python3
"""Render a persisted retrieval-only Evaluation run as a Markdown report.

The API response is deliberately accepted as input instead of querying the
database, so a report is reproducible from the run's request/result snapshot.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from statistics import mean
from typing import Any


def number(value: Any, default: float = 0.0) -> float:
    return float(value) if isinstance(value, (int, float)) else default


def average(rows: list[dict[str, Any]], key: str) -> float:
    values = [number(row.get("metrics", {}).get(key)) for row in rows
              if isinstance(row.get("metrics", {}).get(key), (int, float))]
    return mean(values) if values else 0.0


def pct(value: float) -> str:
    return f"{value:.4f}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path, help="Evaluation run JSON returned by GET /evaluation/runs/{id}")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--basic-run", type=Path, help="Optional 200-row Basic run JSON for paired deltas")
    parser.add_argument("--blueprint", type=Path,
                        default=Path("benchmarks/chinese-enterprise-agentic-retrieval-v1.blueprint.json"))
    parser.add_argument("--basic-r5", type=float, default=0.6067)
    parser.add_argument("--basic-r10", type=float, default=0.6567)
    parser.add_argument("--old-agentic-r5", type=float, default=0.7592)
    parser.add_argument("--old-agentic-r10", type=float, default=0.7925)
    parser.add_argument("--weknora-qwen-r5", type=float, default=0.5767)
    parser.add_argument("--weknora-qwen-r10", type=float, default=0.6150)
    args = parser.parse_args()
    payload = json.loads(args.run.read_text(encoding="utf-8"))
    run = payload["run"]
    rows = payload.get("results", [])
    successful = [row for row in rows if not row.get("errorMessage")]
    failed = [row for row in rows if row.get("errorMessage")]
    metrics = run.get("aggregateMetrics", {})
    basic_by_question: dict[str, dict[str, Any]] = {}
    if args.basic_run:
        basic_payload = json.loads(args.basic_run.read_text(encoding="utf-8"))
        basic_by_question = {str(row.get("question")): row for row in basic_payload.get("results", [])}
    metadata_by_question: dict[str, dict[str, Any]] = {}
    if args.blueprint.exists():
        blueprint = json.loads(args.blueprint.read_text(encoding="utf-8"))
        metadata_by_question = {str(case.get("question")): case.get("metadata", {})
                                for case in blueprint.get("cases", [])}
    paired = {"improved": 0, "tied": 0, "regressed": 0}
    for row in successful:
        basic = basic_by_question.get(str(row.get("question")))
        if not basic:
            continue
        delta = number(row.get("metrics", {}).get("recallAt5")) - number(
            basic.get("metrics", {}).get("recallAt5"))
        paired["improved" if delta > 1e-12 else "regressed" if delta < -1e-12 else "tied"] += 1
    lines = [
        "# GPT-5.5 WeKnora-v2 ReAct Retrieval-only Report",
        "",
        "## Run snapshot",
        "",
        f"- Evaluation run: `{run['id']}`",
        f"- Dataset: `{run['datasetId']}`",
        f"- Status: **{run['status']}**; unique result rows: **{len(rows)}**; successful: **{len(successful)}**; failed: **{len(failed)}**",
        f"- Execution: `{metrics.get('execution', 'AGENTIC_RETRIEVAL_ONLY')}`; answer generation skipped: `{metrics.get('answerGenerationSkipped', True)}`",
        f"- Model Profile: `{metrics.get('modelProfileId', '')}`; Pipeline: `{metrics.get('pipelineVersion', 'agentic-react-v1')}`; Prompt: `{metrics.get('promptVersion', 'weknora-progressive-rag-v1')}`",
        f"- Scope KBs: `{metrics.get('knowledgeBaseIds', [])}`; metadata filters: `{metrics.get('metadataFilterCount', 0)}`; case parallelism: `{metrics.get('caseParallelism', 2)}`",
        "",
        "## Retrieval metrics",
        "",
        "| Metric | GPT-5.5 ReAct | Basic reference | Old fixed Agentic | WeKnora-v2 Qwen reference |",
        "| --- | ---: | ---: | ---: | ---: |",
        f"| Recall@5 | {pct(number(metrics.get('recallAt5')))} | {args.basic_r5:.4f} | {args.old_agentic_r5:.4f} | {args.weknora_qwen_r5:.4f} |",
        f"| Recall@10 | {pct(number(metrics.get('recallAt10')))} | {args.basic_r10:.4f} | {args.old_agentic_r10:.4f} | {args.weknora_qwen_r10:.4f} |",
        f"| Hit@5 | {pct(number(metrics.get('hitAt5')))} | — | — | — |",
        f"| Hit@10 | {pct(number(metrics.get('hitAt10')))} | — | — | — |",
        f"| MRR@5 | {pct(number(metrics.get('mrrAt5')))} | — | — | — |",
        f"| nDCG@5 / @10 | {pct(number(metrics.get('ndcgAt5')))} / {pct(number(metrics.get('ndcgAt10')))} | — | — | — |",
        f"| MAP@5 / @10 | {pct(number(metrics.get('mapAt5')))} / {pct(number(metrics.get('mapAt10')))} | — | — | — |",
        f"| Evidence answer coverage | {pct(number(metrics.get('expectedAnswerCoverage')))} | — | — | — |",
        "",
        "## Paired and grouped diagnostics",
        "",
        f"- Recall@5 versus Basic: improved `{paired['improved']}`, tied `{paired['tied']}`, regressed `{paired['regressed']}`.",
        "",
    ]
    for dimension, label in (("challengeType", "Challenge"), ("sourceProject", "Source"),
                             ("intentCount", "Intent count")):
        groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for row in successful:
            metadata = metadata_by_question.get(str(row.get("question")), {})
            if metadata.get(dimension) is not None:
                groups[str(metadata[dimension])].append(row)
        if not groups:
            continue
        lines.extend([
            f"### {label}", "",
            "| Group | Cases | Recall@5 | Recall@10 | MRR@5 |",
            "| --- | ---: | ---: | ---: | ---: |",
        ])
        for name, group_rows in sorted(groups.items()):
            lines.append(f"| {name} | {len(group_rows)} | {pct(average(group_rows, 'recallAt5'))} | {pct(average(group_rows, 'recallAt10'))} | {pct(average(group_rows, 'reciprocalRankAt5'))} |")
        lines.append("")
    lines.extend([
        "## Agent diagnostics",
        "",
        f"- Tool calls: `{metrics.get('toolCallCount', 0)}`; failures: `{metrics.get('toolFailureCount', 0)}`; budget rejections: `{metrics.get('budgetRejectionCount', 0)}`.",
        f"- Average iterations: `{number(metrics.get('averageIterations')):.2f}`; deep-read compliance: `{pct(number(metrics.get('deepReadComplianceRate')))} `; context compressions: `{metrics.get('contextCompressionCount', 0)}`.",
        f"- Tool coverage Recall: `{pct(number(metrics.get('allToolCoverageRecall')))} `; deep-read Recall: `{pct(number(metrics.get('deepReadRecall')))} `; strict discovery Recall@5/@10: `{pct(number(metrics.get('strictDiscoveryRecallAt5')))} / {pct(number(metrics.get('strictDiscoveryRecallAt10')))} `.",
        f"- Token usage: input `{metrics.get('inputTokens', 0)}`, output `{metrics.get('outputTokens', 0)}`, total `{metrics.get('totalTokens', 0)}`.",
        f"- Latency P50/P95/P99: `{metrics.get('p50LatencyMs', 0)} / {metrics.get('p95LatencyMs', 0)} / {metrics.get('p99LatencyMs', 0)} ms`.",
        "",
        "## Operational acceptance",
        "",
        f"- 200 unique successful cases: **{'PASS' if len(rows) == 200 and not failed and run['status'] == 'COMPLETED' else 'PENDING/FAIL'}**",
        f"- Retrieval-only contract (no final answer/citations): **{'PASS' if metrics.get('answerGenerationSkipped', True) else 'FAIL'}**",
        f"- Effective-version/cross-scope leak count: `{metrics.get('effectiveVersionLeakCount', 0)}` (must be 0).",
        "- Quality metrics are diagnostic only; the hard gates are completion, native Tool Calling, retrieval-only persistence, and zero scope/version leakage.",
        "",
        "## Per-question result audit",
        "",
        "| # | Question | R@5 | R@10 | MRR@5 | Tool calls | Iterations | Error |",
        "| ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |",
    ])
    for index, row in enumerate(rows, 1):
        item = row.get("metrics", {})
        question = str(row.get("question", "")).replace("|", "\\|").replace("\n", " ")
        if len(question) > 100:
            question = question[:97] + "..."
        basic = basic_by_question.get(str(row.get("question")))
        basic_r5 = number(basic.get("metrics", {}).get("recallAt5")) if basic else None
        delta_label = ""
        if basic_r5 is not None:
            delta = number(item.get("recallAt5")) - basic_r5
            delta_label = "↑" if delta > 1e-12 else "↓" if delta < -1e-12 else "="
        escaped_error = str(row.get("errorMessage") or "").replace("|", "\\|")
        lines.append(f"| {index} | {question} {delta_label} | {pct(number(item.get('recallAt5')))} | {pct(number(item.get('recallAt10')))} | {pct(number(item.get('reciprocalRankAt5')))} | {item.get('toolCallCount', 0)} | {item.get('iterationCount', 0)} | {escaped_error} |")
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {len(rows)} result rows to {args.output}")


if __name__ == "__main__":
    main()
