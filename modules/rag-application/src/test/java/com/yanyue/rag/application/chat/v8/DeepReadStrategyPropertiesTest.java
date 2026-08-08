package com.yanyue.rag.application.chat.v8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.domain.agent.v8.DeepReadEvidenceStrategy;
import org.junit.jupiter.api.Test;

class DeepReadStrategyPropertiesTest {
    @Test
    void usesTheValidatedGoalBatchedParentStrategyByDefault() {
        assertEquals(DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT,
                new DeepReadStrategyProperties(null).strategy());
        assertEquals(DeepReadEvidenceStrategy.CANDIDATE_SPAN,
                new DeepReadStrategyProperties("candidate_span").strategy());
        assertEquals(DeepReadEvidenceStrategy.ADAPTIVE_EVIDENCE,
                new DeepReadStrategyProperties("adaptive_evidence").strategy());
        assertEquals(DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT,
                new DeepReadStrategyProperties("goal_batched_parent").strategy());
        assertThrows(IllegalArgumentException.class,
                () -> new DeepReadStrategyProperties("unknown"));
    }

    @Test
    void reportsThePromptBundleThatActuallyRunsForEachStrategy() {
        assertEquals("agentic-v8-request-analysis-v2+deep-read-v1+evidence-judge+answer-v1",
                new DeepReadStrategyProperties("candidate_span").runtimePromptVersion());
        assertEquals("agentic-v8-request-analysis-v2+adaptive-parent-read-v1+evidence-judge+answer-v1",
                new DeepReadStrategyProperties("parent_context").runtimePromptVersion());
        assertEquals("agentic-v8-goal-batch-parent-v4+judge-v1+answer-v2",
                new DeepReadStrategyProperties("goal_batched_parent").runtimePromptVersion());
    }

    @Test
    void runtimePromptVersionsFitThePersistedRunColumn() {
        for (var strategy : DeepReadEvidenceStrategy.values()) {
            assertTrue(new DeepReadStrategyProperties(strategy.name()).runtimePromptVersion().length() <= 80);
        }
    }
}
