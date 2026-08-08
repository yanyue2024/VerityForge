package com.yanyue.rag.contract.evaluation;

import java.time.Instant;
import java.util.UUID;

public record EvaluationNotificationDeliveryView(
        UUID id,
        UUID scheduleId,
        UUID comparisonId,
        String status,
        int attempt,
        int maxAttempts,
        Integer responseStatus,
        String responseBody,
        String errorMessage,
        Instant nextAttemptAt,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt
) {
}
