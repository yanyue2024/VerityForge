package com.yanyue.rag.domain.knowledge;

import java.util.List;
import java.util.UUID;

public record Chunk(
        UUID id,
        UUID documentVersionId,
        UUID parentChunkId,
        ChunkType type,
        int orderIndex,
        String text,
        String renderMarkdown,
        String contextHeader,
        String embeddingText,
        int estimatedTokens,
        String tokenizerName,
        String tokenCountMethod,
        List<UUID> sourceBlockIds,
        String chunkHash,
        String chunkPolicyVersion,
        boolean enabled
) {
    public Chunk {
        renderMarkdown = renderMarkdown == null || renderMarkdown.isBlank() ? text : renderMarkdown;
        contextHeader = contextHeader == null ? "" : contextHeader;
        tokenizerName = tokenizerName == null ? "verityforge-lexical-estimator-v2" : tokenizerName;
        tokenCountMethod = tokenCountMethod == null ? "ESTIMATED" : tokenCountMethod;
        sourceBlockIds = sourceBlockIds == null ? List.of() : List.copyOf(sourceBlockIds);
        if (type == ChunkType.CHILD && parentChunkId == null) {
            throw new IllegalArgumentException("Child chunks require a parent");
        }
    }
}
