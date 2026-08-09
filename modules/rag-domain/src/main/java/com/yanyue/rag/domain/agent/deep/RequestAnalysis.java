package com.yanyue.rag.domain.agent.deep;

import com.yanyue.rag.domain.agent.deep.AnswerConstraint;
import com.yanyue.rag.domain.agent.deep.ObjectiveRequirement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record RequestAnalysis(
        String standaloneObjective,
        List<ObjectiveRequirement> objectiveRequirements,
        List<AnswerConstraint> answerConstraints,
        List<GoalPlan> goals
) {
    public RequestAnalysis {
        standaloneObjective = DeepValidation.requiredText(standaloneObjective, "standaloneObjective", 2_000);
        objectiveRequirements = List.copyOf(DeepValidation.required(objectiveRequirements, "objectiveRequirements"));
        answerConstraints = List.copyOf(DeepValidation.required(answerConstraints, "answerConstraints"));
        goals = List.copyOf(DeepValidation.required(goals, "goals"));
        DeepValidation.sizeBetween(objectiveRequirements, 1, DeepRagLimits.MAX_OBJECTIVE_REQUIREMENTS,
                "objectiveRequirements");
        DeepValidation.sizeBetween(goals, 1, DeepRagLimits.MAX_GOALS, "goals");

        var goalIds = uniqueGoalIds(goals);
        var objectiveIds = new HashSet<UUID>();
        for (var requirement : objectiveRequirements) {
            if (!objectiveIds.add(requirement.id()) || !goalIds.containsAll(requirement.mappedGoalIds())) {
                throw new IllegalArgumentException("objective requirements must be unique and reference known goals");
            }
        }
        for (var constraint : answerConstraints) {
            if (!goalIds.containsAll(constraint.appliesToGoalIds())) {
                throw new IllegalArgumentException("answer constraint references an unknown goal");
            }
        }
    }

    private static Set<UUID> uniqueGoalIds(List<GoalPlan> goals) {
        var ids = new HashSet<UUID>();
        var normalizedQuestions = new HashSet<String>();
        for (var goal : goals) {
            var normalized = goal.question().strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
            if (!ids.add(goal.id()) || !normalizedQuestions.add(normalized)) {
                throw new IllegalArgumentException("goals must have unique IDs and questions");
            }
        }
        return Set.copyOf(ids);
    }
}
