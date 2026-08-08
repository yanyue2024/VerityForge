package com.yanyue.rag.domain.evaluation;

import java.time.Instant;
import java.util.UUID;

public record EvaluationNotificationSummary(
        UUID id,
        UUID comparisonId,
        String status,
        int attempt,
        int maxAttempts,
        Integer responseStatus,
        String errorMessage,
        Instant updatedAt
) {
}
