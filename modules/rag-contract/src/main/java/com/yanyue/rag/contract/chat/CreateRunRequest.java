package com.yanyue.rag.contract.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateRunRequest(
        @NotBlank @Size(max = 20_000) String query,
        RunMode mode,
        KnowledgeScope scope,
        List<MetadataFilter> filters,
        UUID modelProfileId
) {
    public CreateRunRequest {
        mode = mode == null ? RunMode.AUTO : mode;
        scope = scope == null ? KnowledgeScope.all() : scope;
        filters = filters == null ? List.of() : List.copyOf(filters);
    }
}
