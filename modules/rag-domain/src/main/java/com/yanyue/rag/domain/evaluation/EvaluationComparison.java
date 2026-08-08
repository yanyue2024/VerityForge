package com.yanyue.rag.domain.evaluation;

import java.time.Instant;
import java.util.UUID;

public record EvaluationComparison(
        UUID id,
        UUID datasetId,
        UUID fastRunId,
        UUID deepRunId,
        String judgeMode,
        Instant createdAt
) {
}
