package com.yanyue.rag.domain.agent;

import java.util.List;
import java.util.UUID;

public record FactItem(
        UUID id,
        UUID subQuestionId,
        String statement,
        List<UUID> evidenceIds,
        double confidence,
        FactStatus status,
        UUID conflictGroupId,
        List<FactSupport> supports,
        String rejectionReason
) {
    public FactItem {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        supports = supports == null ? List.of() : List.copyOf(supports);
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Fact confidence must be between 0 and 1");
        }
        if (status == FactStatus.ACCEPTED && evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("Accepted facts require evidence");
        }
    }

    public FactItem(
            UUID id,
            UUID subQuestionId,
            String statement,
            List<UUID> evidenceIds,
            double confidence,
            FactStatus status,
            UUID conflictGroupId
    ) {
        this(id, subQuestionId, statement, evidenceIds, confidence, status, conflictGroupId, List.of(), null);
    }
}
