package com.yanyue.rag.domain.agent.deep;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record AnswerConstraint(String description, Set<UUID> appliesToGoalIds) {
    public AnswerConstraint {
        description = DeepValidation.requiredText(description, "description", 500);
        appliesToGoalIds = Set.copyOf(new LinkedHashSet<>(
                DeepValidation.required(appliesToGoalIds, "appliesToGoalIds")));
        if (appliesToGoalIds.isEmpty()) {
            throw new IllegalArgumentException("answer constraint must apply to at least one goal");
        }
    }
}
