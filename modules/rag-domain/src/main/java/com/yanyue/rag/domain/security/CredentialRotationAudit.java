package com.yanyue.rag.domain.security;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CredentialRotationAudit(
        UUID id,
        String activeKeyId,
        UUID rotatedBy,
        int totalCredentials,
        int rotatedCredentials,
        Map<String, Integer> sourceCounts,
        Map<String, Integer> previousKeyCounts,
        Instant createdAt
) {
}
