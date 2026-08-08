package com.yanyue.rag.domain.agent.v4;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record GoalPlan(UUID id, String question, List<RequirementPlan> requirements, SearchQuery initialQuery) {
    public GoalPlan {
        V4Validation.required(id, "id");
        question = V4Validation.requiredText(question, "question", 1_000);
        requirements = List.copyOf(V4Validation.required(requirements, "requirements"));
        V4Validation.sizeBetween(requirements, 1, AgenticV4Limits.MAX_REQUIREMENTS_PER_GOAL, "requirements");
        var ids = new HashSet<UUID>();
        for (var requirement : requirements) {
            if (!id.equals(requirement.goalId()) || !ids.add(requirement.id())) {
                throw new IllegalArgumentException("requirements must be unique and belong to the goal");
            }
        }
        V4Validation.required(initialQuery, "initialQuery");
        if (!id.equals(initialQuery.goalId()) || initialQuery.role() != SearchQueryRole.INITIAL) {
            throw new IllegalArgumentException("initialQuery must be an INITIAL query for this goal");
        }
        if (!ids.containsAll(initialQuery.targetRequirementIds())) {
            throw new IllegalArgumentException("initialQuery targets unknown requirements");
        }
    }

    public Set<UUID> requirementIds() {
        return requirements.stream().map(RequirementPlan::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
