from __future__ import annotations

import copy
import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "render-agentic-v2-report.py"
SPEC = importlib.util.spec_from_file_location("render_agentic_v2_report", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
REPORT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = REPORT
SPEC.loader.exec_module(REPORT)


def runtime_snapshot() -> dict[str, object]:
    model = {
        "id": "profile-1",
        "provider": "OPENAI_COMPATIBLE",
        "modelName": "gpt-5.5",
        "name": "GPT-5.5",
        "revision": "test",
    }
    return {
        "pipelineVersion": REPORT.EXPECTED_PIPELINE_VERSION,
        "promptVersion": REPORT.EXPECTED_PROMPT_VERSION,
        "chatProfileId": "profile-1",
        "chatModel": model,
        "queryRewriteModel": {**model, "id": "rewrite-1"},
        "rerankModel": {**model, "id": "rerank-1"},
    }


def healthy_payload() -> tuple[dict[str, object], list[dict[str, object]]]:
    run = {
        "status": "COMPLETED",
        "aggregateMetrics": {
            "execution": "RAG",
            "requestedMode": "DEEP",
            "caseCount": 1,
            "successfulCases": 1,
            "failedCases": 0,
            "modelProfileId": "profile-1",
        },
    }
    rows = [{
        "evaluationCaseId": "case-1",
        "question": "What is the policy?",
        "metrics": {
            "selectedMode": "DEEP",
            "toolFailureCount": 0,
            "judgeCallCount": 1,
            "runtimeSnapshot": runtime_snapshot(),
            "toolDiagnostics": {
                "deepReadFailureCount": 0,
                "tool.deep_read": {"calls": 1, "failed": 0},
                "tool.evidence_judge": {"calls": 1, "failed": 0},
            },
        },
    }]
    return run, rows


def validate(
        run: dict[str, object], rows: list[dict[str, object]], *,
        allow_tool_failures: bool = False
):
    return REPORT.validate_run(
        run,
        rows,
        1,
        False,
        False,
        False,
        allow_tool_failures,
        REPORT.EXPECTED_PIPELINE_VERSION,
        REPORT.EXPECTED_PROMPT_VERSION,
        "profile-1",
    )


class ToolHealthGateTest(unittest.TestCase):

    def test_healthy_deep_row_passes(self) -> None:
        run, rows = healthy_payload()

        warnings, _, audit = validate(run, rows)

        self.assertEqual([], warnings)
        self.assertTrue(audit.healthy)
        self.assertEqual(1, audit.deep_rows)

    def test_each_degraded_tool_condition_fails_strict_validation(self) -> None:
        mutations = {
            "toolFailureCount": lambda metrics: metrics.update(toolFailureCount=1),
            "deepReadFailureCount": lambda metrics: metrics["toolDiagnostics"].update(
                deepReadFailureCount=1),
            "mandatory Evidence Judge": lambda metrics: (
                metrics.update(judgeCallCount=0),
                metrics["toolDiagnostics"]["tool.evidence_judge"].update(calls=0),
            ),
            "tool.evidence_judge.failed": lambda metrics: metrics["toolDiagnostics"][
                "tool.evidence_judge"].update(failed=1),
        }
        for expected, mutate in mutations.items():
            with self.subTest(expected=expected):
                run, rows = healthy_payload()
                mutate(rows[0]["metrics"])

                with self.assertRaises(REPORT.ReportValidationError) as raised:
                    validate(run, rows)

                self.assertIn(expected, str(raised.exception))

    def test_allow_tool_failures_renders_degradation_as_warnings(self) -> None:
        run, rows = healthy_payload()
        metrics = rows[0]["metrics"]
        metrics["toolFailureCount"] = 2
        metrics["toolDiagnostics"]["deepReadFailureCount"] = 2
        metrics["toolDiagnostics"]["tool.deep_read"]["failed"] = 2
        metrics["toolDiagnostics"]["tool.evidence_judge"]["failed"] = 1

        warnings, _, audit = validate(run, rows, allow_tool_failures=True)

        self.assertFalse(audit.healthy)
        self.assertEqual(2, audit.tool_failure_count)
        self.assertEqual(2, audit.deep_read_failure_count)
        self.assertEqual(1, audit.evidence_judge_failure_count)
        self.assertEqual(3, len(warnings))
        self.assertTrue(all(value.startswith("Tool health gate waived:") for value in warnings))

    def test_auto_fast_row_does_not_require_evidence_judge(self) -> None:
        run, rows = healthy_payload()
        run["aggregateMetrics"]["requestedMode"] = "AUTO"
        metrics = rows[0]["metrics"]
        metrics["selectedMode"] = "FAST"
        metrics["judgeCallCount"] = 0
        metrics["toolDiagnostics"]["tool.evidence_judge"]["calls"] = 0
        metrics.pop("runtimeSnapshot")

        warnings, _, audit = validate(run, rows)

        self.assertEqual([], warnings)
        self.assertTrue(audit.healthy)
        self.assertEqual(0, audit.deep_rows)

    def test_failed_row_is_left_to_the_existing_failure_gate(self) -> None:
        run, rows = healthy_payload()
        failed_run = copy.deepcopy(run)
        failed_rows = copy.deepcopy(rows)
        failed_run["aggregateMetrics"].update(successfulCases=0, failedCases=1)
        failed_rows[0]["errorMessage"] = "evidence judge request failed"
        failed_rows[0]["metrics"]["toolFailureCount"] = 1
        failed_rows[0]["metrics"]["toolDiagnostics"]["tool.evidence_judge"]["failed"] = 1

        warnings, _, audit = REPORT.validate_run(
            failed_run,
            failed_rows,
            1,
            True,
            False,
            False,
            False,
            REPORT.EXPECTED_PIPELINE_VERSION,
            REPORT.EXPECTED_PROMPT_VERSION,
            "profile-1",
        )

        self.assertEqual(["Run contains 1 failed result rows"], warnings)
        self.assertTrue(audit.healthy)
        self.assertEqual(0, audit.successful_rows)


if __name__ == "__main__":
    unittest.main()
