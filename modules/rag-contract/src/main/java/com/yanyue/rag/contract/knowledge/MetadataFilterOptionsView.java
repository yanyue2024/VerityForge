package com.yanyue.rag.contract.knowledge;

import java.util.List;

public record MetadataFilterOptionsView(List<MetadataFilterFieldView> fields) {
    public MetadataFilterOptionsView {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
