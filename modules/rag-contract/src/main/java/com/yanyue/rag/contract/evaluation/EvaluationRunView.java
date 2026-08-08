package com.yanyue.rag.contract.evaluation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EvaluationRunView(
        UUID id,
        UUID datasetId,
        String status,
        Map<String, Object> aggregateMetrics,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
}
