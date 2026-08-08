package com.yanyue.rag.contract.knowledge;

import java.util.List;

public record MetadataFilterFieldView(
        String key,
        String label,
        MetadataFieldType type,
        boolean populated,
        List<String> values
) {
    public MetadataFilterFieldView {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
