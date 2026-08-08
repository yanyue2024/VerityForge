package com.yanyue.rag.contract.security;

import java.util.Map;

public record CredentialRotationStatusView(
        String activeKeyId,
        int totalCredentials,
        int needsRotation,
        int unreadableCredentials,
        Map<String, Integer> credentialsBySource,
        Map<String, Integer> credentialsByKeyId,
        CredentialRotationAuditView lastRotation
) {
}
