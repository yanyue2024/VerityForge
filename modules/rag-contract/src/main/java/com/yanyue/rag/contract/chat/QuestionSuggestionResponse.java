package com.yanyue.rag.contract.chat;

import java.util.List;
import java.util.UUID;

public record QuestionSuggestionResponse(
        UUID batchId,
        String scopeFingerprint,
        RunMode effectiveMode,
        List<QuestionSuggestionView> suggestions,
        QuestionSuggestionEmptyReason emptyReason
) {
    public QuestionSuggestionResponse {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }
}
