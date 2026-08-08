package com.yanyue.rag.domain.agent.v8;

/**
 * Evidence extraction strategy used by the v8 Deep Read stage.
 *
 * <p>The default keeps the production candidate-span behavior. The parent
 * strategies are opt-in experiment variants and preserve the rest of the v8
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
