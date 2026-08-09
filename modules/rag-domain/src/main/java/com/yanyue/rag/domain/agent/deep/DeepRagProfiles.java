package com.yanyue.rag.domain.agent.deep;

/** Immutable quality profiles for the final Deep RAG pipeline. */
public final class DeepRagProfiles {
    public static final String VERSION = "deep-rag-final-limits-v1";
    public static final String FINAL_PROFILE = "DEEP_FINAL_GOAL_BATCHED_PARENT";
    public static final int STRUCTURED_MODEL_OUTPUT_CEILING_TOKENS = 2_200;
    public static final int BATCHED_PARENT_DEEP_READ_OUTPUT_TOKENS = 900;

    private DeepRagProfiles() {
    }

    public static DeepRagLimits defaults() {
        return defaults(DeepReadEvidenceStrategy.CANDIDATE_SPAN);
    }

    public static DeepRagLimits defaults(DeepReadEvidenceStrategy strategy) {
        if (strategy == DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT) return finalProfile();
        var tokens = strategy.batchesParentsByGoal()
                ? new DeepRagLimits.Tokens(
                        2_000, 5_000, 20_000, 10_000, 10_000,
                        2_200, 1_800, 2_200, 2_500, 150_000, 20_000, true)
                : new DeepRagLimits.Tokens(
                        2_000, 5_000, 10_000, 10_000, 10_000,
                        2_200, 700, 2_200, 2_500, 90_000, 12_000, true);
        return new DeepRagLimits(
                DeepRagLimits.Concurrency.defaults(),
                new DeepRagLimits.Retrieval(
                        60, 60, 80, 80, 14, 14, 16, 800, true),
                tokens,
                java.time.Duration.ofSeconds(240),
                true,
                strategy);
    }

    /** The immutable limits validated on the 200-case Chinese enterprise suite. */
    public static DeepRagLimits finalProfile() {
        return new DeepRagLimits(
                new DeepRagLimits.Concurrency(6, 3, 3),
                new DeepRagLimits.Retrieval(
                        60, 60, 80, 80, 14, 14, 16, 800, true),
                new DeepRagLimits.Tokens(
                        2_000, 5_000, 20_000, 10_000, 20_000,
                        2_200, 1_800, 2_200, 4_000, 150_000, 20_000, true),
                java.time.Duration.ofSeconds(240),
                true,
                DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT);
    }

    public static boolean matches(DeepRagLimits limits) {
        if (limits == null) return false;
        var retrieval = limits.retrieval();
        return retrieval.keywordTopK() == 60
                && retrieval.semanticTopK() == 60
                && retrieval.rrfCandidateLimit() == 80
                && retrieval.rerankOutputLimit() == 14
                && retrieval.parentLimitPerGoalPhase() == 14;
    }
}
