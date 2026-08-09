package com.yanyue.rag.domain.agent.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.domain.agent.AgentBudgetLimits;
import com.yanyue.rag.domain.agent.budget.BudgetDimension;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DeepRagLimitsTest {
    @Test
    void parentReadStrategyExpandsCallSlotsWithoutChangingTheRunTokenBudget() {
        var baseline = DeepRagProfiles.defaults();
        var adaptive = DeepRagProfiles.defaults(DeepReadEvidenceStrategy.ADAPTIVE_EVIDENCE);

        assertEquals(3, baseline.maximum(BudgetDimension.PRIMARY_DEEP_READ_CALL));
        assertEquals(42, adaptive.maximum(BudgetDimension.PRIMARY_DEEP_READ_CALL));
        assertEquals(baseline.maximum(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN),
                adaptive.maximum(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN));
        assertEquals(DeepReadEvidenceStrategy.CANDIDATE_SPAN, baseline.deepReadEvidenceStrategy());
    }

    @Test
    void goalBatchedParentStrategyBudgetsCallsByGoalInsteadOfParent() {
        var batched = DeepRagProfiles.finalProfile();

        assertEquals("DEEP_FINAL_GOAL_BATCHED_PARENT", DeepRagProfiles.FINAL_PROFILE);
        assertEquals(60, batched.retrieval().keywordTopK());
        assertEquals(60, batched.retrieval().semanticTopK());
        assertEquals(80, batched.retrieval().rrfCandidateLimit());
        assertEquals(80, batched.retrieval().rerankInputLimit());
        assertEquals(14, batched.retrieval().rerankOutputLimit());
        assertEquals(14, batched.retrieval().parentLimitPerGoalPhase());
        assertEquals(3, batched.maximum(BudgetDimension.PRIMARY_DEEP_READ_CALL));
        assertEquals(3, batched.maximum(BudgetDimension.REPAIR_DEEP_READ_CALL));
        assertEquals(3, batched.maximum(BudgetDimension.EVIDENCE_JUDGE_CALL));
        assertEquals(11, batched.maximum(BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL));
        assertEquals(22, batched.maximum(BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT));
        assertEquals(20_000, batched.tokens().deepReadInput());
        assertEquals(20_000, batched.tokens().finalAnswerInput());
        assertEquals(4_000, batched.tokens().finalAnswerOutput());
        assertEquals(2_200, DeepRagProfiles.STRUCTURED_MODEL_OUTPUT_CEILING_TOKENS);
        assertEquals(900, DeepRagProfiles.BATCHED_PARENT_DEEP_READ_OUTPUT_TOKENS);
        assertEquals(150_000, batched.maximum(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN));
        assertEquals(20_000, batched.maximum(BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN));
        assertEquals(36, batched.acceptedEvidenceLimit());
        assertEquals(18, batched.evidencePerGoalLimit());
        assertEquals(6, batched.evidencePerRequirementLimit());
        assertEquals(3, batched.evidencePerParentAndPhaseLimit());
        assertEquals(36, batched.finalAnswerReferenceLimit());
        assertEquals(36, batched.maximum(BudgetDimension.FINAL_REFERENCE));
        assertEquals(Duration.ofSeconds(240), batched.runDeadline());
        assertEquals(DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT, batched.deepReadEvidenceStrategy());
    }
    @Test
    void conservativeDefaultsStayInsideTheProtocolBoundary() {
        var limits = DeepRagLimits.defaults();

        assertEquals(6, limits.concurrency().searches());
        assertEquals(30, limits.retrieval().keywordTopK());
        assertEquals(30, limits.retrieval().semanticTopK());
        assertEquals(40, limits.retrieval().rrfCandidateLimit());
        assertEquals(40, limits.retrieval().rerankInputLimit());
        assertEquals(8, limits.retrieval().rerankOutputLimit());
        assertEquals(4, limits.retrieval().parentLimitPerGoalPhase());
        assertEquals(8, limits.retrieval().candidateSpanLimit());
        assertEquals(12, DeepRagLimits.MAX_PHYSICAL_SEARCHES);
        assertEquals(12, limits.maximum(BudgetDimension.PHYSICAL_SEARCH));
        assertEquals(60_000, limits.maximum(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN));
        assertEquals(9, limits.maximum(BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL));
        assertEquals(12, limits.maximum(BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT));
        assertInstanceOf(AgentBudgetLimits.class, limits);
        assertEquals(Duration.ofSeconds(120), limits.runDeadline());
    }

    @Test
    void administratorsCanOnlyLowerRetrievalAndConcurrencyLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeepRagLimits.Concurrency(7, 3, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepRagLimits.Retrieval(31, 30, 40, 40, 8, 4, 8, 600));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepRagLimits.Retrieval(30, 30, 20, 21, 8, 4, 8, 600));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepRagLimits.Retrieval(30, 30, 40, 40, 9, 4, 8, 600));
        assertThrows(IllegalArgumentException.class, () -> new DeepRagLimits(
                DeepRagLimits.Concurrency.defaults(), DeepRagLimits.Retrieval.defaults(),
                DeepRagLimits.Tokens.defaults(), Duration.ofSeconds(121)));
    }
}
