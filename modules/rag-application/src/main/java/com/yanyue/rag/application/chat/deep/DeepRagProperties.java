package com.yanyue.rag.application.chat.deep;

import com.yanyue.rag.domain.agent.deep.DeepReadEvidenceStrategy;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeepRagProperties {
    private final DeepReadEvidenceStrategy strategy;

    public DeepRagProperties(
            @Value("${rag.agentic.deep-read-strategy:GOAL_BATCHED_PARENT}") String configuredStrategy
    ) {
        try {
            this.strategy = DeepReadEvidenceStrategy.valueOf(
                    configuredStrategy == null ? "GOAL_BATCHED_PARENT"
                            : configuredStrategy.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unsupported Deep RAG evidence strategy: "
                    + configuredStrategy, failure);
        }
    }

    public DeepReadEvidenceStrategy strategy() {
        return strategy;
    }

    public String runtimePromptVersion() {
        return switch (strategy) {
            case CANDIDATE_SPAN ->
                    "deep-request-analysis-v1+candidate-span-read-v1+evidence-judge-v1+answer-v1";
            case ADAPTIVE_EVIDENCE, PARENT_CONTEXT ->
                    "deep-request-analysis-v1+adaptive-parent-read-v1+evidence-judge-v1+answer-v1";
            case GOAL_BATCHED_PARENT ->
                    "deep-request-analysis-v1+goal-batched-parent-read-v1+evidence-judge-v1+answer-v1";
        };
    }
}
