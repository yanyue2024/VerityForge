package com.yanyue.rag.application.chat.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yanyue.rag.application.chat.deep.EvidenceJudge;
import com.yanyue.rag.domain.agent.deep.GoalStatus;
import com.yanyue.rag.domain.agent.deep.RequirementStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GapActionReducerTest {
    @Test
    void incompleteGoalUsesBoundedDualRouteSearchInsteadOfReadMore() {
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var requirement = new EvidenceJudge.RequirementDecision(
                requirementId, RequirementStatus.MISSING, Set.of());
        var decision = new EvidenceJudge.JudgeDecision(List.of(
                new EvidenceJudge.GoalDecision(
                        goalId, List.of(requirement), null, GoalStatus.NEEDS_REPAIR)), false);

        var actions = new GapActionReducer().reduce(null, decision, null);

        assertEquals(1, actions.size());
        assertEquals(GapActionReducer.Type.SEARCH_MORE, actions.getFirst().type());
        assertEquals(Set.of(requirementId), actions.getFirst().missingRequirementIds());
    }
}
