package com.yanyue.rag.domain.agent.react;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ReactKnowledgeReference(
        UUID id,
        UUID runId,
        UUID toolCallId,
        UUID knowledgeBaseId,
        UUID documentId,
        UUID documentVersionId,
        UUID chunkId,
        String documentTitle,
        String excerpt,
        Integer sourceStart,
        Integer sourceEnd,
        KnowledgeReferenceSource source,
        List<KnowledgeReferenceSource> sources,
        boolean deepRead,
        Double score,
        Map<String, Object> metadata,
        Long firstDiscoveryOrder,
        Long firstDeepReadOrder,
        Instant createdAt,
        Instant updatedAt
) {
    public ReactKnowledgeReference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(documentVersionId, "documentVersionId");
        Objects.requireNonNull(source, "source");
        sources = sources == null || sources.isEmpty() ? List.of(source) : List.copyOf(sources);
        documentTitle = documentTitle == null ? "" : documentTitle;
        excerpt = excerpt == null ? "" : excerpt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String referenceKey() {
        return chunkId == null ? "document:" + documentId : "chunk:" + chunkId;
    }
}
