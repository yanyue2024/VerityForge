package com.yanyue.rag.domain.chunking;

import java.util.List;

public record ChunkQualityIssue(
        String code,
        ChunkQualityStatus severity,
        String message,
        List<String> chunkIds
) {
    public ChunkQualityIssue {
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
    }
}
