package com.yanyue.rag.domain.evaluation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EvaluationCaseAttempt(
        UUID id,
        UUID evaluationRunId,
        UUID evaluationCaseId,
        UUID ragRunId,
        int attemptNumber,
        String status,
        UUID previousAttemptId,
        Map<String, Object> metrics,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
    public EvaluationCaseAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evaluationRunId, "evaluationRunId");
        Objects.requireNonNull(evaluationCaseId, "evaluationCaseId");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        if (status == null || !java.util.Set.of("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
            throw new IllegalArgumentException("Unsupported evaluation attempt status: " + status);
        }
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
