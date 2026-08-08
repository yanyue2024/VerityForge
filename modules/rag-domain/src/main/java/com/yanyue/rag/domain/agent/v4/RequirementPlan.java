package com.yanyue.rag.domain.agent.v4;

import java.util.UUID;

public record RequirementPlan(UUID id, UUID goalId, String description) {
    public RequirementPlan {
        V4Validation.required(id, "id");
        V4Validation.required(goalId, "goalId");
        description = V4Validation.requiredText(description, "description", 500);
    }
}
