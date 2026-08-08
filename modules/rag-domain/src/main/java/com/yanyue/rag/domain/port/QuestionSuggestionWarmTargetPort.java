package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.UUID;

public interface QuestionSuggestionWarmTargetPort {
    List<WarmTarget> findEnabledTargets();

    record WarmTarget(UUID organizationId, UUID userId, List<UUID> knowledgeBaseIds) {
        public WarmTarget {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        }
    }
}
