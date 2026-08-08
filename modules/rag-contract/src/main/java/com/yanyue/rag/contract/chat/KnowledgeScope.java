package com.yanyue.rag.contract.chat;

import java.util.List;
import java.util.UUID;

public record KnowledgeScope(List<UUID> knowledgeBaseIds, List<UUID> documentIds) {
    public KnowledgeScope {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }

    public static KnowledgeScope all() {
        return new KnowledgeScope(List.of(), List.of());
    }
}
