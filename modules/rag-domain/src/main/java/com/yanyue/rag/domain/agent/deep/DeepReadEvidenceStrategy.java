package com.yanyue.rag.domain.agent.deep;

/**
 * Evidence extraction strategy used by the final Deep Read stage.
 *
 * <p>The default keeps the production candidate-span behavior. The parent
 * strategies are opt-in experiment variants and preserve the rest of the final
 * state machine.</p>
 */
public enum DeepReadEvidenceStrategy {
    CANDIDATE_SPAN,
    ADAPTIVE_EVIDENCE,
    PARENT_CONTEXT,
    GOAL_BATCHED_PARENT;

    public boolean readsParentsIndividually() {
        return this == ADAPTIVE_EVIDENCE || this == PARENT_CONTEXT;
    }

    public boolean readsParentContexts() {
        return this != CANDIDATE_SPAN;
    }

    public boolean batchesParentsByGoal() {
        return this == GOAL_BATCHED_PARENT;
    }
}
