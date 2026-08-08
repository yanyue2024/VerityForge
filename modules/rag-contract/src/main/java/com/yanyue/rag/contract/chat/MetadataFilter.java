package com.yanyue.rag.contract.chat;

import jakarta.validation.constraints.NotBlank;
import com.yanyue.rag.contract.knowledge.MetadataFieldType;

public record MetadataFilter(
        @NotBlank String field,
        FilterOperator operator,
        Object value,
        MetadataFieldType valueType
) {
    public MetadataFilter(String field, FilterOperator operator, Object value) {
        this(field, operator, value, null);
    }
}
