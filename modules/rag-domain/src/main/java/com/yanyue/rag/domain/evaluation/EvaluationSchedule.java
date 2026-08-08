package com.yanyue.rag.domain.evaluation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EvaluationSchedule(
        UUID id,
        UUID organizationId,
        UUID datasetId,
        UUID createdBy,
        String name,
        int cadenceMinutes,
        boolean enabled,
        Map<String, Object> request,
        boolean webhookEnabled,
        String webhookUrl,
        String webhookSecretCiphertext,
        EvaluationNotificationSummary lastNotification,
        Instant nextRunAt,
        Instant lastRunAt,
        UUID lastComparisonId,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public EvaluationSchedule {
        request = request == null ? Map.of() : Map.copyOf(request);
    }
}
