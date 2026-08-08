package com.yanyue.rag.application.chat.v7;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yanyue.rag.application.chat.v5.EvidenceJudgeReasonerV5;
import com.yanyue.rag.domain.agent.v5.GoalStatus;
import com.yanyue.rag.domain.agent.v5.RequirementStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GapActionReducerV7Test {
    @Test
    void incompleteGoalUsesBoundedDualRouteSearchInsteadOfReadMore() {
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var requirement = new EvidenceJudgeReasonerV5.RequirementDecision(
                requirementId, RequirementStatus.MISSING, Set.of());
        var decision = new EvidenceJudgeReasonerV5.JudgeDecision(List.of(
                new EvidenceJudgeReasonerV5.GoalDecision(
                        goalId, List.of(requirement), null, GoalStatus.NEEDS_REPAIR)), false);

        var actions = new GapActionReducerV7().reduce(null, decision, null);

        assertEquals(1, actions.size());
        assertEquals(GapActionReducerV7.Type.SEARCH_MORE, actions.getFirst().type());
        assertEquals(Set.of(requirementId), actions.getFirst().missingRequirementIds());
    }
}
