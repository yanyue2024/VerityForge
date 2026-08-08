package com.yanyue.rag.domain.agent.react;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ReactStep(
        UUID id,
        UUID runId,
        int stepNumber,
        ReactStepStatus status,
        String actionSummary,
        String assistantContent,
        String finishReason,
        Map<String, Object> providerMetadata,
        Map<String, Object> tokenUsage,
        Instant startedAt,
        Instant completedAt
) {
    public ReactStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        if (stepNumber < 1) throw new IllegalArgumentException("stepNumber must be positive");
        actionSummary = actionSummary == null ? "" : actionSummary;
        assistantContent = assistantContent == null ? "" : assistantContent;
        providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
        tokenUsage = tokenUsage == null ? Map.of() : Map.copyOf(tokenUsage);
        startedAt = startedAt == null ? Instant.now() : startedAt;
    }
}
