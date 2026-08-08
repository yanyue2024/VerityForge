package com.yanyue.rag.domain.security;

import java.util.UUID;

public record StoredCredential(
        CredentialLocation location,
        UUID id,
        String ciphertext
) {
}
