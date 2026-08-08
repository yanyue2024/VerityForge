package com.yanyue.rag.domain.port;

import java.util.UUID;
import java.time.Duration;

public interface StructuredReasoningModelPort {
    String completeJson(UUID profileId, String operation, String systemPrompt, String userPrompt);

    default String completeJson(
            UUID profileId,
            String operation,
            String systemPrompt,
            String userPrompt,
            Duration timeout
    ) {
        return completeJson(profileId, operation, systemPrompt, userPrompt);
    }

    default String completeJson(
            UUID profileId,
            String operation,
            String systemPrompt,
            String userPrompt,
            Duration timeout,
            int maximumOutputTokens
    ) {
        return completeJson(profileId, operation, systemPrompt, userPrompt, timeout);
    }

    default String completeJson(
            UUID profileId,
            String operation,
            String systemPrompt,
            String userPrompt,
            Duration timeout,
            int maximumOutputTokens,
            int maximumPhysicalAttempts
    ) {
        return completeJson(profileId, operation, systemPrompt, userPrompt, timeout, maximumOutputTokens);
    }

    default String completeJson(
            UUID profileId,
            String operation,
            String systemPrompt,
            String userPrompt,
            Duration timeout,
            int maximumOutputTokens,
            int maximumPhysicalAttempts,
            double temperature
    ) {
        return completeJson(profileId, operation, systemPrompt, userPrompt, timeout, maximumOutputTokens,
                maximumPhysicalAttempts);
    }
}
