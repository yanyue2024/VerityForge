package com.yanyue.rag.contract.knowledge;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;

public record UpdateDocumentMetadataRequest(
        @NotNull Map<String, Object> metadata,
        Instant validFrom,
        Instant validTo
) {
    public UpdateDocumentMetadataRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
