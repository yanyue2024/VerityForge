package com.yanyue.rag.domain.agent.v4;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record ObjectiveRequirement(UUID id, String description, boolean mandatory, Set<UUID> mappedGoalIds) {
    public ObjectiveRequirement {
        V4Validation.required(id, "id");
        description = V4Validation.requiredText(description, "description", 500);
        mappedGoalIds = Set.copyOf(new LinkedHashSet<>(V4Validation.required(mappedGoalIds, "mappedGoalIds")));
        if (mandatory && mappedGoalIds.isEmpty()) {
            throw new IllegalArgumentException("mandatory objective requirement must map to at least one goal");
        }
        if (mappedGoalIds.size() > AgenticV4Limits.MAX_GOALS) {
            throw new IllegalArgumentException("objective requirement maps to too many goals");
        }
    }
}
