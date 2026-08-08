package com.yanyue.rag.contract.evaluation;

import java.time.Instant;
import java.util.UUID;

public record EvaluationRunSummaryView(
        UUID id,
        UUID datasetId,
        String name,
        String datasetName,
        String status,
        String mode,
        int totalCases,
        int completedCases,
        int failedCases,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
}
