package com.yanyue.rag.domain.port;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DocumentMetadataPort {
    Optional<MetadataContext> findContext(UUID organizationId, UUID userId, UUID documentVersionId);

    MetadataRevision update(
            UUID organizationId,
            UUID changedBy,
            UUID documentVersionId,
            Map<String, Object> metadata,
            Instant validFrom,
            Instant validTo
    );

    record MetadataContext(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID documentVersionId,
            boolean current,
            String status,
            Map<String, Object> metadata,
            Instant validFrom,
            Instant validTo
    ) {
    }

    record MetadataRevision(
            UUID revisionId,
            UUID documentVersionId,
            Map<String, Object> metadata,
            Instant validFrom,
            Instant validTo,
            Instant createdAt
    ) {
    }
}
