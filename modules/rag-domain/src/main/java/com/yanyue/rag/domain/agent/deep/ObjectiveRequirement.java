package com.yanyue.rag.domain.agent.deep;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record ObjectiveRequirement(UUID id, String description, boolean mandatory, Set<UUID> mappedGoalIds) {
    public ObjectiveRequirement {
        DeepValidation.required(id, "id");
        description = DeepValidation.requiredText(description, "description", 500);
        mappedGoalIds = Set.copyOf(new LinkedHashSet<>(DeepValidation.required(mappedGoalIds, "mappedGoalIds")));
        if (mandatory && mappedGoalIds.isEmpty()) {
            throw new IllegalArgumentException("mandatory objective requirement must map to at least one goal");
        }
        if (mappedGoalIds.size() > DeepRagLimits.MAX_GOALS) {
            throw new IllegalArgumentException("objective requirement maps to too many goals");
        }
    }
}
