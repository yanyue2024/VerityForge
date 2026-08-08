package com.yanyue.rag.domain.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record UploadRegistration(
        UUID uploadId,
        UUID organizationId,
        UUID actorUserId,
        UUID knowledgeBaseId,
        UUID documentId,
        UUID documentVersionId,
        String title,
        String fileName,
        String contentType,
        long byteSize,
        String declaredSha256,
        String objectKey,
        Map<String, Object> metadata,
        Instant validFrom,
        Instant validTo,
        Instant createdAt
) {
    public UploadRegistration {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
