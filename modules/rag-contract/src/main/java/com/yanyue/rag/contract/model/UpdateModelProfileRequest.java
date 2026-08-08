package com.yanyue.rag.contract.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record UpdateModelProfileRequest(
        @NotNull ModelProvider provider,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 160) String modelName,
        @Size(max = 500) String baseUrl,
        @Size(max = 4096) String apiKey,
        boolean clearApiKey,
        @NotNull Map<String, Object> settings,
        boolean enabled
) {
}
