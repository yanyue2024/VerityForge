package com.yanyue.rag.domain.retrieval;

import com.yanyue.rag.contract.chat.MetadataFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RetrievalScope(
        UUID organizationId,
        UUID userId,
        boolean accessControlBypass,
        List<UUID> knowledgeBaseIds,
        List<UUID> documentIds,
        List<MetadataFilter> metadataFilters,
        Instant effectiveAt
) {
    public RetrievalScope {
        if (organizationId == null) throw new IllegalArgumentException("organizationId is required");
        if (!accessControlBypass && userId == null) {
            throw new IllegalArgumentException("userId is required for access-controlled retrieval");
        }
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        metadataFilters = metadataFilters == null ? List.of() : List.copyOf(metadataFilters);
        effectiveAt = effectiveAt == null ? Instant.now() : effectiveAt;
    }

    public static RetrievalScope forUser(
            UUID organizationId,
            UUID userId,
            List<UUID> knowledgeBaseIds,
            List<UUID> documentIds,
            List<MetadataFilter> metadataFilters,
            Instant effectiveAt
    ) {
        return new RetrievalScope(organizationId, userId, false, knowledgeBaseIds, documentIds,
                metadataFilters, effectiveAt);
    }

    public static RetrievalScope system(
            UUID organizationId,
            List<UUID> knowledgeBaseIds,
            List<UUID> documentIds,
            List<MetadataFilter> metadataFilters,
            Instant effectiveAt
    ) {
        return new RetrievalScope(organizationId, null, true, knowledgeBaseIds, documentIds,
                metadataFilters, effectiveAt);
    }
}
