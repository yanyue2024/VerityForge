package com.yanyue.rag.contract.evaluation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateEvaluationDatasetRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 2_000) String description
) {
    public CreateEvaluationDatasetRequest {
        description = description == null ? "" : description;
    }
}
