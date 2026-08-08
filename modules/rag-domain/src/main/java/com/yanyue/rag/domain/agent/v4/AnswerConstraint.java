package com.yanyue.rag.domain.agent.v4;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record AnswerConstraint(String description, Set<UUID> appliesToGoalIds) {
    public AnswerConstraint {
        description = V4Validation.requiredText(description, "description", 500);
        appliesToGoalIds = Set.copyOf(new LinkedHashSet<>(
                V4Validation.required(appliesToGoalIds, "appliesToGoalIds")));
        if (appliesToGoalIds.isEmpty()) {
            throw new IllegalArgumentException("answer constraint must apply to at least one goal");
        }
    }
}
