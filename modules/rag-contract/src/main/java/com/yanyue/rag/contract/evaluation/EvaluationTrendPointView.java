package com.yanyue.rag.contract.evaluation;

import java.time.Instant;
import java.util.UUID;

public record EvaluationTrendPointView(
        UUID comparisonId,
        UUID datasetId,
        EvaluationJudgeMode judgeMode,
        EvaluationRunView fast,
        EvaluationRunView deep,
        Instant createdAt
) {
}
