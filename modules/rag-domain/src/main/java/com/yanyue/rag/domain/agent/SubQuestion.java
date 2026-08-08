package com.yanyue.rag.domain.agent;

import java.util.List;
import java.util.UUID;

public record SubQuestion(
        UUID id,
        String question,
        List<String> expectedEvidence,
        int priority,
        List<UUID> dependencies,
        SearchMode searchMode,
        String completionCondition
) {
    public SubQuestion {
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        searchMode = searchMode == null ? SearchMode.HYBRID : searchMode;
        completionCondition = completionCondition == null || completionCondition.isBlank()
                ? "至少一个已深读且支持事实的有效证据族"
                : completionCondition.strip();
    }

    public SubQuestion(UUID id, String question, List<String> expectedEvidence, int priority) {
        this(id, question, expectedEvidence, priority, List.of(), SearchMode.HYBRID,
                "至少一个已深读且支持事实的有效证据族");
    }
}
