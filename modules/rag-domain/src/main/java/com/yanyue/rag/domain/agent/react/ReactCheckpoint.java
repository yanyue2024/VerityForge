package com.yanyue.rag.domain.agent.react;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ReactCheckpoint(
        UUID runId,
        int checkpointVersion,
        String phase,
        int currentStep,
        List<Map<String, Object>> messages,
        Map<String, Object> budget,
        Set<UUID> seenChunkIds,
        List<UUID> knowledgeReferenceIds,
        List<Map<String, Object>> pendingToolCalls,
        Map<String, Object> runtimeState,
        Instant updatedAt
) {
    public static final int CURRENT_VERSION = 2;

    public ReactCheckpoint {
        Objects.requireNonNull(runId, "runId");
        if (checkpointVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported ReAct checkpoint version: " + checkpointVersion);
        }
        if (phase == null || phase.isBlank()) throw new IllegalArgumentException("phase is required");
        if (currentStep < 0) throw new IllegalArgumentException("currentStep cannot be negative");
        messages = immutableMaps(messages);
        budget = budget == null ? Map.of() : Map.copyOf(budget);
        seenChunkIds = seenChunkIds == null ? Set.of() : Set.copyOf(seenChunkIds);
        knowledgeReferenceIds = knowledgeReferenceIds == null ? List.of() : List.copyOf(knowledgeReferenceIds);
        pendingToolCalls = immutableMaps(pendingToolCalls);
        runtimeState = runtimeState == null ? Map.of() : Map.copyOf(runtimeState);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    private static List<Map<String, Object>> immutableMaps(List<Map<String, Object>> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> value == null ? Map.<String, Object>of() : Map.copyOf(value)).toList();
    }
}
