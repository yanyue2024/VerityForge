package com.yanyue.rag.domain.agent.deep;

import java.util.UUID;

public record RequirementPlan(UUID id, UUID goalId, String description) {
    public RequirementPlan {
        DeepValidation.required(id, "id");
        DeepValidation.required(goalId, "goalId");
        description = DeepValidation.requiredText(description, "description", 500);
    }
}
