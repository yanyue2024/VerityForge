package com.yanyue.rag.contract.evaluation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EvaluationDatasetView(
        UUID id,
        String name,
        String description,
        int caseCount,
        int runCount,
        String lastRunStatus,
        Map<String, Object> lastMetrics,
        Instant createdAt
) {
}
