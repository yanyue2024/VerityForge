package com.yanyue.rag.contract.evaluation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateEvaluationCaseRequest(
        @NotBlank @Size(max = 20_000) String question,
        @Size(max = 20_000) String expectedAnswer,
        List<UUID> expectedDocumentIds,
        Map<String, Object> metadata
) {
    public CreateEvaluationCaseRequest {
        expectedDocumentIds = expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
