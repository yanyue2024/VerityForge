package com.yanyue.rag.contract.chat;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record QuestionSuggestionRequest(
        RunMode mode,
        KnowledgeScope scope,
        @Size(max = 20) List<MetadataFilter> filters,
        boolean refresh,
        UUID currentBatchId
) {
    public QuestionSuggestionRequest {
        mode = mode == null ? RunMode.AUTO : mode;
        scope = scope == null ? KnowledgeScope.all() : scope;
        filters = filters == null ? List.of() : List.copyOf(filters);
    }
}
