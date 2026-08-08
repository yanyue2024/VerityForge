package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface StreamingAnswerModelPort {
    GenerationResult generate(UUID profileId, AnswerRequest request, Consumer<String> onDelta);

    default GenerationResult generate(
            UUID profileId,
            AnswerRequest request,
            Consumer<String> onDelta,
            int maximumPhysicalAttempts
    ) {
        return generate(profileId, request, onDelta);
    }

    record AnswerRequest(
            String question,
            String standaloneQuery,
            List<AnswerEvidence> evidence,
            List<String> personalizationMemory,
            int timeoutSeconds,
            int maximumOutputTokens,
            String systemInstruction,
            List<String> conversationHistory,
            Double temperature
    ) {
        public AnswerRequest {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            personalizationMemory = personalizationMemory == null ? List.of() : List.copyOf(personalizationMemory);
            conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
            if (maximumOutputTokens < 1) throw new IllegalArgumentException("maximumOutputTokens must be positive");
            if (temperature != null && (temperature < 0 || temperature > 2)) {
                throw new IllegalArgumentException("temperature must be between 0 and 2");
            }
        }

        public AnswerRequest(
                String question,
                String standaloneQuery,
                List<AnswerEvidence> evidence,
                List<String> personalizationMemory,
                int timeoutSeconds,
                int maximumOutputTokens
        ) {
            this(question, standaloneQuery, evidence, personalizationMemory, timeoutSeconds, maximumOutputTokens,
                    null, List.of(), null);
        }

        public AnswerRequest(
                String question,
                String standaloneQuery,
                List<AnswerEvidence> evidence,
                List<String> personalizationMemory,
                int timeoutSeconds
        ) {
            this(question, standaloneQuery, evidence, personalizationMemory, timeoutSeconds, 2_048,
                    null, List.of(), null);
        }

        public AnswerRequest(String question, String standaloneQuery, List<AnswerEvidence> evidence, int timeoutSeconds) {
            this(question, standaloneQuery, evidence, List.of(), timeoutSeconds, 2_048,
                    null, List.of(), null);
        }
    }

    record AnswerEvidence(
            String evidenceId,
            String documentTitle,
            UUID documentVersionId,
            UUID chunkId,
            String text
    ) {
    }

    record GenerationResult(String content, Integer inputTokens, Integer outputTokens, String finishReason) {
    }
}
