package com.yanyue.rag.domain.agent.v7;

import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;

/**
 * v7 uses the same immutable ledger contract as v5, with a separate quality
 * profile so v5 defaults and historical runs remain reproducible.
 */
public final class AgenticV7Limits {
    public static final String VERSION = "agentic-v7-limits-v3";

    private AgenticV7Limits() {
    }

    public static AgenticV5Limits defaults() {
        return AgenticV5Limits.v7Defaults();
    }
}
