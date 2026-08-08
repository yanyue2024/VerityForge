package com.yanyue.rag.domain.agent.v4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestAnalysisTest {
    @Test
    void acceptsAtMostThreeGoalsWithMappedObjectiveRequirements() {
        var goals = List.of(goal("目标一"), goal("目标二"), goal("目标三"));
        var analysis = new RequestAnalysis(
                "独立问题",
                List.of(new ObjectiveRequirement(UUID.randomUUID(), "覆盖三个目标", true,
                        Set.of(goals.get(0).id(), goals.get(1).id(), goals.get(2).id()))),
                List.of(new AnswerConstraint("分别回答", Set.of(goals.get(0).id(), goals.get(1).id()))),
                goals
        );

        assertEquals(3, analysis.goals().size());
    }

    @Test
    void rejectsFourthGoalAndUnknownMappings() {
        var goals = new ArrayList<>(List.of(goal("目标一"), goal("目标二"), goal("目标三")));
        goals.add(goal("目标四"));

        assertThrows(IllegalArgumentException.class, () -> new RequestAnalysis(
                "独立问题",
                List.of(new ObjectiveRequirement(UUID.randomUUID(), "目标", true,
                        Set.of(goals.getFirst().id()))),
                List.of(),
                goals));

        var onlyGoal = goal("合法目标");
        assertThrows(IllegalArgumentException.class, () -> new RequestAnalysis(
                "独立问题",
                List.of(new ObjectiveRequirement(UUID.randomUUID(), "错误映射", true,
                        Set.of(UUID.randomUUID()))),
                List.of(),
                List.of(onlyGoal)));
    }

    @Test
    void enforcesQueryProtocolAndRequirementOwnership() {
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new SearchQuery(
                UUID.randomUUID(), goalId, ResearchPhase.REPAIR, SearchQueryRole.REPAIR_KEYWORD,
                "补检", SearchMode.SEMANTIC, Set.of(requirementId)));
        assertThrows(IllegalArgumentException.class, () -> new GoalPlan(
                goalId,
                "目标",
                List.of(new RequirementPlan(requirementId, UUID.randomUUID(), "不属于目标")),
                new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY, SearchQueryRole.INITIAL,
                        "初始检索", SearchMode.KEYWORD, Set.of(requirementId))));
    }

    private GoalPlan goal(String question) {
        var goalId = UUID.randomUUID();
        var requirement = new RequirementPlan(UUID.randomUUID(), goalId, question + "证据面");
        var query = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.INITIAL, question, SearchMode.SEMANTIC, Set.of(requirement.id()));
        return new GoalPlan(goalId, question, List.of(requirement), query);
    }
}
