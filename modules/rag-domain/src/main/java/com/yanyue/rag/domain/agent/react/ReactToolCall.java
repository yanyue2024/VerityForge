package com.yanyue.rag.domain.agent.react;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ReactToolCall(
        UUID id,
        UUID runId,
        UUID stepId,
        String providerCallId,
        int callIndex,
        String toolName,
        Map<String, Object> arguments,
        ReactToolCallStatus status,
        String output,
        Map<String, Object> resultData,
        Map<String, Object> error,
        Integer resultCount,
        Long latencyMs,
        Instant startedAt,
        Instant completedAt
) {
    public ReactToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepId, "stepId");
        if (providerCallId == null || providerCallId.isBlank()) {
            throw new IllegalArgumentException("providerCallId is required");
        }
        if (callIndex < 0) throw new IllegalArgumentException("callIndex cannot be negative");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName is required");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        Objects.requireNonNull(status, "status");
        resultData = resultData == null ? Map.of() : Map.copyOf(resultData);
        error = error == null ? Map.of() : Map.copyOf(error);
        if (resultCount != null && resultCount < 0) throw new IllegalArgumentException("resultCount cannot be negative");
        if (latencyMs != null && latencyMs < 0) throw new IllegalArgumentException("latencyMs cannot be negative");
    }
}
