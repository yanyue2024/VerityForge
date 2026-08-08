package com.yanyue.rag.domain.evaluation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EvaluationResult(
        UUID id,
        UUID evaluationRunId,
        UUID evaluationCaseId,
        UUID ragRunId,
        Map<String, Object> metrics,
        String errorMessage,
        Instant createdAt
) {
    public EvaluationResult {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
