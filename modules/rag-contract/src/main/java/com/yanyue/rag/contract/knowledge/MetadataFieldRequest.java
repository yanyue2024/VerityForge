package com.yanyue.rag.contract.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MetadataFieldRequest(
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{0,62}") String key,
        @NotBlank @Size(max = 120) String label,
        @NotNull MetadataFieldType type,
        boolean required,
        boolean filterable,
        @Size(max = 200) List<@Size(max = 500) String> allowedValues
) {
    public MetadataFieldRequest {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }
}
