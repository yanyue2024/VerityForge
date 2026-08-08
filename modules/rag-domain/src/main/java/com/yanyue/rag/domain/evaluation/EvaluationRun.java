package com.yanyue.rag.domain.evaluation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EvaluationRun(
        UUID id,
        UUID datasetId,
        EvaluationRunStatus status,
        Map<String, Object> aggregateMetrics,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
    public EvaluationRun {
        aggregateMetrics = aggregateMetrics == null ? Map.of() : Map.copyOf(aggregateMetrics);
    }
}
