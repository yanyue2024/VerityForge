package com.yanyue.rag.domain.model;

import java.util.UUID;

public record EmbeddingModelReference(
        UUID profileId,
        String modelId,
        String modelVersion,
        int dimension
) {
    public EmbeddingModelReference {
        if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("Embedding model ID is required");
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("Embedding model version is required");
        }
        if (dimension <= 0 || dimension > 16_384) {
            throw new IllegalArgumentException("Embedding dimension is out of range");
        }
    }
}
