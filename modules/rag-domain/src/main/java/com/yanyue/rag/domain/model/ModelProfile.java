package com.yanyue.rag.domain.model;

import com.yanyue.rag.contract.model.ModelProfileTestStatus;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ModelProfile(
        UUID id,
        UUID organizationId,
        ModelProfileType profileType,
        ModelProvider provider,
        String name,
        String modelName,
        String baseUrl,
        String encryptedApiKey,
        Map<String, Object> settings,
        boolean enabled,
        ModelProfileTestStatus testStatus,
        Instant lastTestedAt,
        String lastTestMessage,
        Map<String, Object> capabilities,
        Instant createdAt,
        Instant updatedAt
) {
    public ModelProfile {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }

    public boolean hasApiKey() {
        return encryptedApiKey != null && !encryptedApiKey.isBlank();
    }
}
