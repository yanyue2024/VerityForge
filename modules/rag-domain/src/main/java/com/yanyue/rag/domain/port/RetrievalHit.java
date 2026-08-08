package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.UUID;

public record RetrievalHit(
        UUID chunkId,
        UUID parentChunkId,
        UUID documentId,
        UUID documentVersionId,
        String documentTitle,
        String text,
        double score,
        List<String> sources,
        Integer pageNumber,
        Integer sourceStart,
        Integer sourceEnd
) {
    public RetrievalHit(
            UUID chunkId,
            UUID parentChunkId,
            UUID documentId,
            UUID documentVersionId,
            String documentTitle,
            String text,
            double score,
            List<String> sources
    ) {
        this(chunkId, parentChunkId, documentId, documentVersionId, documentTitle, text, score, sources,
                null, null, null);
    }

    public RetrievalHit {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public RetrievalHit withScore(double nextScore, List<String> nextSources) {
        return new RetrievalHit(chunkId, parentChunkId, documentId, documentVersionId, documentTitle, text,
                nextScore, nextSources, pageNumber, sourceStart, sourceEnd);
    }

    public RetrievalHit withText(String nextText) {
        return new RetrievalHit(chunkId, parentChunkId, documentId, documentVersionId, documentTitle, nextText,
                score, sources, pageNumber, sourceStart, sourceEnd);
    }

    /**
     * 保留物理召回结果标识，同时把引用收窄为父块扩展上下文中的精确原文片段。
     */
    public RetrievalHit withTextAndSource(String nextText, Integer nextSourceStart, Integer nextSourceEnd) {
        return new RetrievalHit(chunkId, parentChunkId, documentId, documentVersionId, documentTitle, nextText,
                score, sources, pageNumber, nextSourceStart, nextSourceEnd);
    }
}
