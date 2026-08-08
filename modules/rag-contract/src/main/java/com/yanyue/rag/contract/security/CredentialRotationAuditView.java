package com.yanyue.rag.contract.security;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CredentialRotationAuditView(
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
