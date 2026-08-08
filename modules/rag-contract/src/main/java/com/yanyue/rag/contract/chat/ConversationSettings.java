package com.yanyue.rag.contract.chat;

import java.util.List;

public record ConversationSettings(
        RunMode mode,
        KnowledgeScope scope,
        List<MetadataFilter> filters
) {
    public ConversationSettings {
        mode = mode == null ? RunMode.AUTO : mode;
        scope = scope == null ? KnowledgeScope.all() : scope;
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public static ConversationSettings defaults() {
        return new ConversationSettings(RunMode.AUTO, KnowledgeScope.all(), List.of());
    }
}
