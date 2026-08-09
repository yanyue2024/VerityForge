package com.yanyue.rag.domain.agent.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoverageStateTest {
    @Test
    void exposesOnlyUnfinishedGoalsAndSupportsRepairExhaustion() {
        var complete = UUID.randomUUID();
        var repair = UUID.randomUUID();
        var requirement = UUID.randomUUID();
        var state = new CoverageState(
                Map.of(requirement, RequirementStatus.MISSING),
                Map.of(complete, GoalStatus.SATISFIED_LOCKED, repair, GoalStatus.NEEDS_REPAIR), false);

        assertEquals(java.util.List.of(repair), state.incompleteGoalIds());
        assertEquals(GoalStatus.REPAIR_EXHAUSTED, state.afterRepair().goalStatuses().get(repair));
        assertEquals(GoalStatus.SATISFIED_LOCKED, state.afterRepair().goalStatuses().get(complete));
    }

    @Test
    void refusesToExhaustAStateThatHasNoRepairGoal() {
        var state = new CoverageState(Map.of(),
                Map.of(UUID.randomUUID(), GoalStatus.SATISFIED_LOCKED), false);

        assertThrows(IllegalStateException.class, state::afterRepair);
    }
}
