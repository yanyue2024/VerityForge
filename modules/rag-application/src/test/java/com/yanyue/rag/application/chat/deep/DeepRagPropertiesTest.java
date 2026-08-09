package com.yanyue.rag.application.chat.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.domain.agent.deep.DeepReadEvidenceStrategy;
import org.junit.jupiter.api.Test;

class DeepRagPropertiesTest {
    @Test
    void usesTheValidatedGoalBatchedParentStrategyByDefault() {
        assertEquals(DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT,
                new DeepRagProperties(null).strategy());
        assertEquals(DeepReadEvidenceStrategy.CANDIDATE_SPAN,
                new DeepRagProperties("candidate_span").strategy());
        assertEquals(DeepReadEvidenceStrategy.ADAPTIVE_EVIDENCE,
                new DeepRagProperties("adaptive_evidence").strategy());
        assertEquals(DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT,
                new DeepRagProperties("goal_batched_parent").strategy());
        assertThrows(IllegalArgumentException.class,
                () -> new DeepRagProperties("unknown"));
    }

    @Test
    void reportsThePromptBundleThatActuallyRunsForEachStrategy() {
        assertEquals("deep-request-analysis-v1+candidate-span-read-v1+evidence-judge-v1+answer-v1",
                new DeepRagProperties("candidate_span").runtimePromptVersion());
        assertEquals("deep-request-analysis-v1+adaptive-parent-read-v1+evidence-judge-v1+answer-v1",
                new DeepRagProperties("parent_context").runtimePromptVersion());
        assertEquals("deep-request-analysis-v1+goal-batched-parent-read-v1+evidence-judge-v1+answer-v1",
                new DeepRagProperties("goal_batched_parent").runtimePromptVersion());
    }

    @Test
    void runtimePromptVersionsFitThePersistedRunColumn() {
        for (var strategy : DeepReadEvidenceStrategy.values()) {
            assertTrue(new DeepRagProperties(strategy.name()).runtimePromptVersion().length() <= 80);
        }
    }
}
