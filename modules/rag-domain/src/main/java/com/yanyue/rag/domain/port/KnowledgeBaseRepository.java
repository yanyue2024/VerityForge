package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.knowledge.KnowledgeBase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeBaseRepository {
    KnowledgeBase save(KnowledgeBase knowledgeBase);
    Optional<KnowledgeBase> findById(UUID organizationId, UUID id);
    List<KnowledgeBase> findAll(UUID organizationId);
    java.util.Map<UUID, KnowledgeBaseCounts> counts(UUID organizationId, UUID userId);
    Optional<KnowledgeBaseDeletion> delete(UUID organizationId, UUID id);

    record KnowledgeBaseCounts(long documentCount, long chunkCount, long readyCount,
                               long processingCount, long failedCount, Instant activityAt) {
    }

    record KnowledgeBaseDeletion(List<String> objectKeys) {
    }
}
