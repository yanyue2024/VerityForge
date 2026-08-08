package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.UUID;

public record EmbeddingVector(
        UUID chunkId,
        String modelId,
        String modelVersion,
        List<Float> values
) {
    public EmbeddingVector {
        values = List.copyOf(values);
    }
}
