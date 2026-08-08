package com.yanyue.rag.contract.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MetadataSchemaView(
        UUID id,
        UUID knowledgeBaseId,
        int version,
        List<MetadataFieldRequest> fields,
        boolean active,
        Instant createdAt
) {
}
