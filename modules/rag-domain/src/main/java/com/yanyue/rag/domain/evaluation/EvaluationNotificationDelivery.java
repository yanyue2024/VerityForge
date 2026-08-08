package com.yanyue.rag.domain.evaluation;

import java.time.Instant;
import java.util.UUID;

public record EvaluationNotificationDelivery(
        UUID id,
        UUID organizationId,
        UUID scheduleId,
        UUID comparisonId,
        UUID datasetId,
        String scheduleName,
        String datasetName,
        String webhookUrl,
        String signingSecretCiphertext,
        String status,
        int attempt,
        int maxAttempts,
        Integer responseStatus,
        String responseBody,
        String errorMessage,
        Instant nextAttemptAt,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt,
        EvaluationRun fastRun,
        EvaluationRun deepRun
) {
}
