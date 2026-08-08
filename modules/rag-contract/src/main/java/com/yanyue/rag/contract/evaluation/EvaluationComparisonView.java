package com.yanyue.rag.contract.evaluation;

import java.time.Instant;
import java.util.UUID;

public record EvaluationComparisonView(
        UUID id,
        UUID datasetId,
        EvaluationRunView fastRun,
        EvaluationRunView deepRun,
        EvaluationJudgeMode judgeMode,
        Instant createdAt
) {
}
