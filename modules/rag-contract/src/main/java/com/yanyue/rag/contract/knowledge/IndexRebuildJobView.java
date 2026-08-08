package com.yanyue.rag.contract.knowledge;

import java.time.Instant;
import java.util.UUID;

public record IndexRebuildJobView(
        UUID id,
        UUID indexGenerationId,
        IndexRebuildStatus status,
        int totalChunks,
        int completedChunks,
        int reusedChunks,
        int failedChunks,
        int attempt,
        int maxAttempts,
        Instant nextAttemptAt,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
}
