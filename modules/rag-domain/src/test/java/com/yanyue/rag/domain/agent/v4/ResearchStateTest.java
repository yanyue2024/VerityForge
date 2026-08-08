package com.yanyue.rag.domain.agent.v4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResearchStateTest {
    @Test
    void coveredRequirementAndLockedGoalCannotReopen() {
        var requirement = RequirementState.unassessed(UUID.randomUUID())
                .transitionTo(RequirementStatus.COVERED);
        var goal = GoalState.active(UUID.randomUUID()).transitionTo(GoalStatus.SATISFIED_LOCKED);

        assertThrows(IllegalStateException.class,
                () -> requirement.transitionTo(RequirementStatus.MISSING));
        assertThrows(IllegalStateException.class,
                () -> goal.transitionTo(GoalStatus.ACTIVE));
    }

    @Test
    void onlySingleSpanCompletableTargetCanCloseWithoutSecondJudge() {
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var directTarget = RepairTarget.open(UUID.randomUUID(), goalId, requirementId, "可由单段补齐",
                RepairCompletionMode.SINGLE_SPAN_COMPLETABLE);
        var reviewTarget = RepairTarget.open(UUID.randomUUID(), goalId, requirementId, "需要综合复核",
                RepairCompletionMode.REVIEW_REQUIRED);

        assertEquals(RepairTargetStatus.SATISFIED, directTarget.apply(TargetEffect.COMPLETE).status());
        assertEquals(RepairTargetStatus.OPEN, reviewTarget.apply(TargetEffect.CONTRIBUTES).status());
        assertThrows(IllegalArgumentException.class, () -> reviewTarget.apply(TargetEffect.COMPLETE));
    }

    @Test
    void missingRequirementCanOnlyBecomeCoveredOrExhausted() {
        var state = RequirementState.unassessed(UUID.randomUUID()).transitionTo(RequirementStatus.MISSING);

        assertEquals(RequirementStatus.NOT_FOUND_WITHIN_BUDGET,
                state.transitionTo(RequirementStatus.NOT_FOUND_WITHIN_BUDGET).status());
        assertThrows(IllegalStateException.class, () -> state.transitionTo(RequirementStatus.CONFLICTING));
    }
}
