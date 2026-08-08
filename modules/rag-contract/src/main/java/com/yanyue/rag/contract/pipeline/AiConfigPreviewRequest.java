package com.yanyue.rag.contract.pipeline;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiConfigPreviewRequest(
        @NotBlank @Size(max = 1200) String query
) { }
