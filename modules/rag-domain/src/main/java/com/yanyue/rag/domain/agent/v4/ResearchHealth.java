package com.yanyue.rag.domain.agent.v4;

public enum ResearchHealth {
    COMPLETED_WITH_EVIDENCE,
    COMPLETED_EMPTY,
    DEGRADED_NON_BLOCKING,
    EVIDENCE_MAY_BE_HIDDEN,
    SKIPPED_NOT_REQUIRED,
    SKIPPED_BUDGET,
    DEADLINE_EXCEEDED,
    CANCELLED;

    public boolean permitsNoEvidenceConclusion() {
        return this == COMPLETED_EMPTY || this == SKIPPED_NOT_REQUIRED || this == DEGRADED_NON_BLOCKING;
    }

    public boolean mayHideEvidence() {
        return this == EVIDENCE_MAY_BE_HIDDEN || this == SKIPPED_BUDGET
                || this == DEADLINE_EXCEEDED || this == CANCELLED;
    }
}
