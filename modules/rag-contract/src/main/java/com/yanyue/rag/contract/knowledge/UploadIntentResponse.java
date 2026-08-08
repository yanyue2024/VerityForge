package com.yanyue.rag.contract.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record UploadIntentResponse(
        UUID uploadId,
        UUID documentId,
        UUID documentVersionId,
        String method,
        String uploadUrl,
        Map<String, String> headers,
        Instant expiresAt
) {
}
