package com.yanyue.rag.contract.evaluation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;

public record EvaluationResultView(
        UUID id,
        UUID evaluationCaseId,
        UUID ragRunId,
        String question,
        String expectedAnswer,
        List<UUID> expectedDocumentIds,
        Map<String, Object> caseMetadata,
        Map<String, Object> metrics,
        String errorMessage,
        Instant createdAt
) {
    public EvaluationResultView {
        expectedDocumentIds = expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
        caseMetadata = caseMetadata == null ? Map.of() : Map.copyOf(caseMetadata);
    }
}
