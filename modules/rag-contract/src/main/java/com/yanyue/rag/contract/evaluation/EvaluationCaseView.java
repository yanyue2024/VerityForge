package com.yanyue.rag.contract.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvaluationCaseView(
        UUID id,
        UUID datasetId,
        String question,
        String expectedAnswer,
        List<UUID> expectedDocumentIds,
        Map<String, Object> metadata,
        long position
) {
}
