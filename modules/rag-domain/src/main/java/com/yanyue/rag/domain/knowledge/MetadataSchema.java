package com.yanyue.rag.domain.knowledge;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record MetadataSchema(UUID knowledgeBaseId, int version, List<MetadataField> fields) {
    public MetadataSchema {
        if (version < 1) {
            throw new IllegalArgumentException("Metadata schema version must be positive");
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
        var keys = new HashSet<String>();
        if (fields.stream().anyMatch(field -> !keys.add(field.key()))) {
            throw new IllegalArgumentException("Metadata schema contains duplicate keys");
        }
    }
}
