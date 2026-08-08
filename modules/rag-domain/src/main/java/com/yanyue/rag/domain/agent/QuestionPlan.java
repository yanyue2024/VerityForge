package com.yanyue.rag.domain.agent;

import java.util.List;
import java.util.UUID;

public record QuestionPlan(UUID runId, String objective, List<SubQuestion> subQuestions) {
    public QuestionPlan {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
        if (subQuestions.size() > 6) {
            throw new IllegalArgumentException("Question plan exceeds the default sub-question budget");
        }
    }
}
