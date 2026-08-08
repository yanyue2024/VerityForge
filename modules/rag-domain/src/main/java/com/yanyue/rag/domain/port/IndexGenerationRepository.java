package com.yanyue.rag.domain.port;

import com.yanyue.rag.contract.knowledge.IndexGenerationStatus;
import com.yanyue.rag.contract.knowledge.IndexRebuildStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IndexGenerationRepository {
    boolean knowledgeBaseExists(UUID organizationId, UUID knowledgeBaseId);

    boolean hasActiveRebuild(UUID knowledgeBaseId);

    String chunkPolicyVersion(UUID knowledgeBaseId);

    GenerationRecord createBuildingGeneration(
            UUID knowledgeBaseId,
            UUID embeddingProfileId,
            String modelId,
            String modelVersion,
            int dimension,
            String chunkPolicyVersion
    );

    RebuildJobRecord createRebuildJob(UUID organizationId, UUID knowledgeBaseId, UUID generationId);

    List<GenerationRecord> findAll(UUID organizationId, UUID knowledgeBaseId);

    GenerationRecord activate(UUID organizationId, UUID knowledgeBaseId, UUID generationId);

    Optional<RebuildJobRecord> findJobByGeneration(UUID generationId);

    record GenerationRecord(
            UUID id,
            UUID knowledgeBaseId,
            int generationNumber,
            IndexGenerationStatus status,
            UUID embeddingProfileId,
            String modelId,
            String modelVersion,
            int dimension,
            String chunkPolicyVersion,
            long vectorCount,
            Instant createdAt,
            Instant activatedAt,
            Instant retiredAt
    ) {
    }

    record RebuildJobRecord(
            UUID id,
            UUID generationId,
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
}
