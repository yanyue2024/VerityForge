package com.yanyue.rag.domain.agent;

import java.util.List;
import java.util.UUID;

public record EvidenceItem(
        UUID id,
        UUID subQuestionId,
        UUID documentId,
        UUID documentVersionId,
        UUID chunkId,
        String quote,
        int sourceStart,
        int sourceEnd,
        double retrievalScore,
        boolean deepRead,
        List<String> retrievalSources
) {
    public EvidenceItem {
        retrievalSources = retrievalSources == null ? List.of() : List.copyOf(retrievalSources);
    }
}
