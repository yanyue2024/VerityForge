package com.yanyue.rag.domain.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvaluationCase(
        UUID id,
        UUID datasetId,
        String question,
        String expectedAnswer,
        List<UUID> expectedDocumentIds,
        Map<String, Object> metadata,
        long position
) {
    public EvaluationCase {
        expectedDocumentIds = expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public EvaluationCase(
            UUID id,
            UUID datasetId,
            String question,
            String expectedAnswer,
            List<UUID> expectedDocumentIds,
            Map<String, Object> metadata
    ) {
        this(id, datasetId, question, expectedAnswer, expectedDocumentIds, metadata, 0);
    }
}
