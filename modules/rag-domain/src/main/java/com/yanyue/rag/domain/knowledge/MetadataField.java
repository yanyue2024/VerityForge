package com.yanyue.rag.domain.knowledge;

import java.util.List;

public record MetadataField(
        String key,
        String label,
        MetadataValueType type,
        boolean required,
        boolean filterable,
        List<String> allowedValues
) {
    public MetadataField {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Metadata key must be lower snake case");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Metadata label is required");
        }
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }
}
