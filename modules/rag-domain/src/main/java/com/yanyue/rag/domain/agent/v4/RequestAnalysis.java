package com.yanyue.rag.domain.agent.v4;

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
        standaloneObjective = V4Validation.requiredText(standaloneObjective, "standaloneObjective", 2_000);
        objectiveRequirements = List.copyOf(V4Validation.required(objectiveRequirements, "objectiveRequirements"));
        answerConstraints = List.copyOf(V4Validation.required(answerConstraints, "answerConstraints"));
        goals = List.copyOf(V4Validation.required(goals, "goals"));
        V4Validation.sizeBetween(objectiveRequirements, 1, AgenticV4Limits.MAX_OBJECTIVE_REQUIREMENTS,
                "objectiveRequirements");
        V4Validation.sizeBetween(goals, 1, AgenticV4Limits.MAX_GOALS, "goals");

        Set<UUID> goalIds = uniqueGoalIds(goals);
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
        var questions = new HashSet<String>();
        for (var goal : goals) {
            if (!ids.add(goal.id()) || !questions.add(goal.question())) {
                throw new IllegalArgumentException("goals must have unique IDs and questions");
            }
        }
        return Set.copyOf(ids);
    }
}
