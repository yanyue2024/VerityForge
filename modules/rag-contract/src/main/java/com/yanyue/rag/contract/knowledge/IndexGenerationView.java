package com.yanyue.rag.contract.knowledge;

import java.time.Instant;
import java.util.UUID;

public record IndexGenerationView(
        UUID id,
        int generationNumber,
        IndexGenerationStatus status,
        UUID embeddingProfileId,
        String embeddingModelId,
        String embeddingModelVersion,
        int embeddingDimension,
        String chunkPolicyVersion,
        long vectorCount,
        IndexRebuildJobView rebuildJob,
        Instant createdAt,
        Instant activatedAt,
        Instant retiredAt
) {
}
