package com.yanyue.rag.contract.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DocumentMetadataRevisionView(
        UUID revisionId,
        UUID documentVersionId,
        Map<String, Object> metadata,
        Instant validFrom,
        Instant validTo,
        boolean embeddingChanged,
        Instant createdAt
) {
}
