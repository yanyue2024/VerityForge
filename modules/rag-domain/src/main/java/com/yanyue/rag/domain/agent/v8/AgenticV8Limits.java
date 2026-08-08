package com.yanyue.rag.domain.agent.v8;

import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;

/**
 * Quality profile for the v8 controlled Agentic RAG pipeline.
 *
 * <p>v8 keeps the v7 state machine and termination rules, but gives each
 * independent Goal a wider retrieval funnel. The wider limits are deliberately
 * represented as a separate profile so persisted v7 runs remain reproducible.
 */
public final class AgenticV8Limits {
    public static final String VERSION = "agentic-v8-final-limits-v2";
    public static final String FINAL_PROFILE = "V8_FINAL_GOAL_BATCHED_PARENT";
    public static final int STRUCTURED_MODEL_OUTPUT_CEILING_TOKENS = 2_200;
    public static final int BATCHED_PARENT_DEEP_READ_OUTPUT_TOKENS = 900;

    private AgenticV8Limits() {
    }

    public static AgenticV5Limits defaults() {
        return defaults(DeepReadEvidenceStrategy.CANDIDATE_SPAN);
    }

    public static AgenticV5Limits defaults(DeepReadEvidenceStrategy strategy) {
        if (strategy == DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT) return finalProfile();
        var tokens = strategy.batchesParentsByGoal()
                ? new AgenticV5Limits.Tokens(
                        2_000, 5_000, 20_000, 10_000, 10_000,
                        2_200, 1_800, 2_200, 2_500, 150_000, 20_000, true)
                : new AgenticV5Limits.Tokens(
                        2_000, 5_000, 10_000, 10_000, 10_000,
                        2_200, 700, 2_200, 2_500, 90_000, 12_000, true);
        return new AgenticV5Limits(
                AgenticV5Limits.Concurrency.defaults(),
                new AgenticV5Limits.Retrieval(
                        60, 60, 80, 80, 14, 14, 16, 800, true),
                tokens,
                java.time.Duration.ofSeconds(240),
                true,
                strategy);
    }

    /** The immutable limits used by the v8 profile validated on the 200-case suite. */
    public static AgenticV5Limits finalProfile() {
        return new AgenticV5Limits(
                new AgenticV5Limits.Concurrency(6, 3, 3),
                new AgenticV5Limits.Retrieval(
                        60, 60, 80, 80, 14, 14, 16, 800, true),
                new AgenticV5Limits.Tokens(
                        2_000, 5_000, 20_000, 10_000, 20_000,
                        2_200, 1_800, 2_200, 4_000, 150_000, 20_000, true),
                java.time.Duration.ofSeconds(240),
                true,
                DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT);
    }

    public static boolean matches(AgenticV5Limits limits) {
        if (limits == null) return false;
        var retrieval = limits.retrieval();
        return retrieval.keywordTopK() == 60
                && retrieval.semanticTopK() == 60
                && retrieval.rrfCandidateLimit() == 80
                && retrieval.rerankOutputLimit() == 14
                && retrieval.parentLimitPerGoalPhase() == 14;
    }
}
