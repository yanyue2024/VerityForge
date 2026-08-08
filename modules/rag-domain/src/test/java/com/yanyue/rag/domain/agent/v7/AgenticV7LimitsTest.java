package com.yanyue.rag.domain.agent.v7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgenticV7LimitsTest {
    @Test
    void precisionProfileKeepsExpandedRetrievalButBoundsGenerativeDeepRead() {
        var limits = AgenticV7Limits.defaults();

        assertTrue(limits.expandedProfile());
        assertTrue(!limits.expandedEvidenceProfile());
        assertEquals(40, limits.retrieval().keywordTopK());
        assertEquals(60, limits.retrieval().rrfCandidateLimit());
        assertEquals(12, limits.retrieval().rerankOutputLimit());
        assertEquals(6, limits.retrieval().parentLimitPerGoalPhase());
        assertEquals(8, limits.retrieval().candidateSpanLimit());
        assertEquals(600, limits.retrieval().candidateSpanTokens());
        assertEquals(6_000, limits.tokens().deepReadInput());
        assertEquals(36, limits.maximum(BudgetDimension.PARENT_READ));
        assertEquals(48, limits.maximum(BudgetDimension.CANDIDATE_SPAN_OFFERED));
        assertEquals(18, limits.maximum(BudgetDimension.ACCEPTED_EVIDENCE));
        assertEquals(12, limits.maximum(BudgetDimension.FINAL_REFERENCE));
        assertEquals(9, limits.maximum(BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL));
        assertEquals(12, limits.maximum(BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT));
        assertEquals(Duration.ofSeconds(210), limits.runDeadline());
        assertEquals("agentic-v7-limits-v3", AgenticV7Limits.VERSION);
    }

    @Test
    void expandedProfileAcceptsTheV7DeadlineButRejectsMoreThanTheHardBoundary() {
        var accepted = new AgenticV5Limits(
                AgenticV5Limits.Concurrency.defaults(),
                AgenticV5Limits.Retrieval.v7PrecisionDefaults(),
                AgenticV5Limits.Tokens.v7PrecisionDefaults(),
                Duration.ofSeconds(210),
                true);

        assertEquals(Duration.ofSeconds(210), accepted.runDeadline());
        assertThrows(IllegalArgumentException.class, () -> new AgenticV5Limits(
                AgenticV5Limits.Concurrency.defaults(),
                AgenticV5Limits.Retrieval.v7PrecisionDefaults(),
                AgenticV5Limits.Tokens.v7PrecisionDefaults(),
                Duration.ofSeconds(241),
                true));
    }
}
