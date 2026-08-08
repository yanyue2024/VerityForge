package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only, scope-enforced storage operations exposed to the ReAct tools. */
public interface AgentKnowledgeToolPort {
    List<KnowledgeChunk> grepChunks(String expression, RetrievalScope scope, int limit);

    List<KnowledgeChunk> listKnowledgeChunks(
            UUID knowledgeId, UUID chunkId, RetrievalScope scope, int offset, int limit);

    List<DocumentInfo> getDocumentInfo(List<UUID> knowledgeIds, RetrievalScope scope);

    record KnowledgeChunk(UUID knowledgeBaseId, RetrievalHit hit, Map<String, Object> metadata) {
        public KnowledgeChunk {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record DocumentInfo(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID documentVersionId,
            String title,
            String sourceName,
            String sourceType,
            String versionLabel,
            String owner,
            String businessDomain,
            List<String> tags,
            Map<String, Object> metadata
    ) {
        public DocumentInfo {
            tags = tags == null ? List.of() : List.copyOf(tags);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
