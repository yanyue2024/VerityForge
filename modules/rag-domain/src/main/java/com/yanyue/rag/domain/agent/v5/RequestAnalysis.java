package com.yanyue.rag.domain.agent.v5;

import com.yanyue.rag.domain.agent.v4.AnswerConstraint;
import com.yanyue.rag.domain.agent.v4.ObjectiveRequirement;
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
        standaloneObjective = V5Validation.requiredText(standaloneObjective, "standaloneObjective", 2_000);
        objectiveRequirements = List.copyOf(V5Validation.required(objectiveRequirements, "objectiveRequirements"));
        answerConstraints = List.copyOf(V5Validation.required(answerConstraints, "answerConstraints"));
        goals = List.copyOf(V5Validation.required(goals, "goals"));
        V5Validation.sizeBetween(objectiveRequirements, 1, AgenticV5Limits.MAX_OBJECTIVE_REQUIREMENTS,
                "objectiveRequirements");
        V5Validation.sizeBetween(goals, 1, AgenticV5Limits.MAX_GOALS, "goals");

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
