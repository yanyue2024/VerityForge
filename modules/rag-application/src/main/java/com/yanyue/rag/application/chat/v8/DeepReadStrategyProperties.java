package com.yanyue.rag.application.chat.v8;

import com.yanyue.rag.domain.agent.v8.DeepReadEvidenceStrategy;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeepReadStrategyProperties {
    private final DeepReadEvidenceStrategy strategy;

    public DeepReadStrategyProperties(
            @Value("${rag.agentic.deep-read-strategy:GOAL_BATCHED_PARENT}") String configuredStrategy
    ) {
        try {
            this.strategy = DeepReadEvidenceStrategy.valueOf(
                    configuredStrategy == null ? "GOAL_BATCHED_PARENT"
                            : configuredStrategy.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unsupported v8 Deep Read evidence strategy: "
                    + configuredStrategy, failure);
        }
    }

    public DeepReadEvidenceStrategy strategy() {
        return strategy;
    }

    public String runtimePromptVersion() {
        return switch (strategy) {
            case CANDIDATE_SPAN ->
                    "agentic-v8-request-analysis-v2+deep-read-v1+evidence-judge+answer-v1";
            case ADAPTIVE_EVIDENCE, PARENT_CONTEXT ->
                    "agentic-v8-request-analysis-v2+adaptive-parent-read-v1+evidence-judge+answer-v1";
            case GOAL_BATCHED_PARENT ->
                    "agentic-v8-goal-batch-parent-v4+judge-v1+answer-v2";
        };
    }
}
