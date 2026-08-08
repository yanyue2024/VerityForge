package com.yanyue.rag.domain.agent.v4;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record GoalState(UUID goalId, GoalStatus status) {
    private static final Map<GoalStatus, Set<GoalStatus>> TRANSITIONS = Map.of(
            GoalStatus.ACTIVE, EnumSet.of(GoalStatus.SATISFIED_LOCKED, GoalStatus.NEEDS_REPAIR,
                    GoalStatus.CONFLICTED),
            GoalStatus.NEEDS_REPAIR, EnumSet.of(GoalStatus.SATISFIED_LOCKED, GoalStatus.PARTIAL_EXHAUSTED),
            GoalStatus.CONFLICTED, EnumSet.of(GoalStatus.PARTIAL_EXHAUSTED),
            GoalStatus.SATISFIED_LOCKED, EnumSet.of(GoalStatus.SATISFIED_LOCKED),
            GoalStatus.PARTIAL_EXHAUSTED, EnumSet.of(GoalStatus.PARTIAL_EXHAUSTED)
    );

    public GoalState {
        V4Validation.required(goalId, "goalId");
        V4Validation.required(status, "status");
    }

    public static GoalState active(UUID goalId) {
        return new GoalState(goalId, GoalStatus.ACTIVE);
    }

    public GoalState transitionTo(GoalStatus target) {
        V4Validation.required(target, "target");
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new IllegalStateException("illegal goal transition from " + status + " to " + target);
        }
        return new GoalState(goalId, target);
    }
}
