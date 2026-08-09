package com.yanyue.rag.domain.agent.budget;

import com.yanyue.rag.domain.agent.AgentBudgetLimits;
import java.time.Duration;
import java.util.Objects;

/** Budget boundary for a single answer-only generation. */
public record AnswerBudgetLimits(
        Duration runDeadline,
        int maximumAttempts,
        int maximumOutputTokens
) implements AgentBudgetLimits {
    public AnswerBudgetLimits {
        Objects.requireNonNull(runDeadline, "runDeadline");
        if (runDeadline.isZero() || runDeadline.isNegative()) {
            throw new IllegalArgumentException("runDeadline must be positive");
        }
        if (maximumAttempts < 1 || maximumOutputTokens < 1) {
            throw new IllegalArgumentException("answer budget limits must be positive");
        }
    }

    @Override
    public long maximum(BudgetDimension dimension) {
        return switch (dimension) {
            case FINAL_ANSWER_CALL, GENERATIVE_LLM_LOGICAL_CALL -> 1;
            case GENERATIVE_LLM_PHYSICAL_ATTEMPT -> maximumAttempts;
            case GENERATIVE_LLM_OUTPUT_TOKEN -> maximumOutputTokens;
            default -> 0;
        };
    }
}
