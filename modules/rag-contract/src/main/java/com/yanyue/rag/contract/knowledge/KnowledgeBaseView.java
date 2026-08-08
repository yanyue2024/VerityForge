package com.yanyue.rag.contract.knowledge;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeBaseView(
        UUID id,
        String name,
        String description,
        long documentCount,
        long chunkCount,
        long readyCount,
        long processingCount,
        long failedCount,
        Instant updatedAt
) {
}
