package com.yanyue.rag.domain.evaluation;

import java.util.Map;
import java.util.UUID;

public record EvaluationRunLineage(
        UUID runId,
        UUID lineageRootId,
        UUID resumedFromRunId,
        int attemptNumber,
        Map<String, Object> requestSnapshot
) {
    public EvaluationRunLineage {
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        requestSnapshot = requestSnapshot == null ? Map.of() : Map.copyOf(requestSnapshot);
    }
}
