package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.knowledge.MetadataField;
import java.util.List;
import java.util.UUID;

public interface MetadataFilterValueRepository {
    FieldValues values(
            UUID organizationId,
            UUID userId,
            List<UUID> knowledgeBaseIds,
            MetadataField field
    );

    record FieldValues(boolean populated, List<String> values) {
        public FieldValues {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
