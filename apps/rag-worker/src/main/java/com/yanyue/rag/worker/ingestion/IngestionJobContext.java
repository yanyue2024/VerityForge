package com.yanyue.rag.worker.ingestion;

import com.yanyue.rag.domain.knowledge.ChunkPolicy;
import java.util.UUID;
import java.util.Map;

record IngestionJobContext(
        UUID jobId,
        int attempt,
        UUID organizationId,
        UUID knowledgeBaseId,
        UUID documentId,
        UUID documentVersionId,
        String objectKey,
        String fileName,
        String contentType,
        String declaredSha256,
        String parserProfile,
        Map<String, Object> parserOptions,
        ChunkPolicy chunkPolicy
) {
    IngestionJobContext {
        parserProfile = parserProfile == null || parserProfile.isBlank() ? "AUTO" : parserProfile;
        parserOptions = parserOptions == null ? Map.of() : Map.copyOf(parserOptions);
    }
}
