package com.yanyue.rag.domain.agent.react;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ReactRankedDocument(
        int rank,
        UUID documentId,
        UUID documentVersionId,
        String documentTitle,
        boolean deepRead,
        long firstDiscoveryOrder,
        Long firstDeepReadOrder,
        Double bestScore,
        List<KnowledgeReferenceSource> sources,
        List<UUID> chunkIds
) {
    public ReactRankedDocument {
        if (rank < 1) throw new IllegalArgumentException("rank must be positive");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(documentVersionId, "documentVersionId");
        documentTitle = documentTitle == null ? "" : documentTitle;
        sources = sources == null ? List.of() : List.copyOf(sources);
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
    }
}
