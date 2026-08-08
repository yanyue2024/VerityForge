package com.yanyue.rag.contract.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateKnowledgeBaseRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description
) {
}
