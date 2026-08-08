package com.yanyue.rag.contract.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ModelProfileView(
        UUID id,
        ModelProfileType profileType,
        ModelProvider provider,
        String name,
        String modelName,
        String baseUrl,
        boolean hasApiKey,
        Map<String, Object> settings,
        boolean enabled,
        ModelProfileTestStatus testStatus,
        Instant lastTestedAt,
        String lastTestMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
