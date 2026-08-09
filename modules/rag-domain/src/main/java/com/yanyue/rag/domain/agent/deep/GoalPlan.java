package com.yanyue.rag.domain.agent.deep;

import com.yanyue.rag.domain.agent.deep.RequirementPlan;
import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record GoalPlan(
        UUID id,
        String question,
        List<RequirementPlan> requirements,
        QueryPair primaryQueryPair
) {
    public GoalPlan {
        DeepValidation.required(id, "id");
        question = DeepValidation.requiredText(question, "question", 1_000);
        requirements = List.copyOf(DeepValidation.required(requirements, "requirements"));
        DeepValidation.sizeBetween(requirements, 1, DeepRagLimits.MAX_REQUIREMENTS_PER_GOAL, "requirements");
        var requirementIds = new HashSet<UUID>();
        for (var requirement : requirements) {
            if (!id.equals(requirement.goalId()) || !requirementIds.add(requirement.id())) {
                throw new IllegalArgumentException("requirements must be unique and belong to the goal");
            }
        }
        DeepValidation.required(primaryQueryPair, "primaryQueryPair");
        if (!id.equals(primaryQueryPair.goalId()) || primaryQueryPair.phase() != ResearchPhase.PRIMARY) {
            throw new IllegalArgumentException("primary query pair must belong to this goal and PRIMARY phase");
        }
        if (!requirementIds.equals(primaryQueryPair.keywordQuery().targetRequirementIds())) {
            throw new IllegalArgumentException("both primary queries must cover all goal requirements");
        }
    }

    public Set<UUID> requirementIds() {
        return requirements.stream().map(RequirementPlan::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public List<SearchQuery> primaryQueries() {
        return primaryQueryPair.queries();
    }
}
