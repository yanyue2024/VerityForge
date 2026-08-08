package com.yanyue.rag.domain.agent.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.domain.agent.v4.AnswerConstraint;
import com.yanyue.rag.domain.agent.v4.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.v4.RequirementPlan;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestAnalysisTest {
    @Test
    void acceptsAtMostThreeGoalsAndSixPrimaryQueries() {
        var goals = List.of(goal("目标一"), goal("目标二"), goal("目标三"));
        var goalIds = goals.stream().map(GoalPlan::id).collect(java.util.stream.Collectors.toSet());

        var analysis = new RequestAnalysis("独立问题",
                List.of(new ObjectiveRequirement(UUID.randomUUID(), "覆盖全部目标", true, goalIds)),
                List.of(new AnswerConstraint("分别回答", goalIds)), goals);

        assertEquals(3, analysis.goals().size());
        assertEquals(6, analysis.goals().stream().mapToInt(value -> value.primaryQueries().size()).sum());
    }

    @Test
    void rejectsFourthGoalUnknownMappingAndDuplicateQuestion() {
        var goals = new ArrayList<>(List.of(goal("目标一"), goal("目标二"), goal("目标三")));
        goals.add(goal("目标四"));
        assertThrows(IllegalArgumentException.class, () -> analysis(goals,
                Set.of(goals.getFirst().id())));

        var onlyGoal = goal("合法目标");
        assertThrows(IllegalArgumentException.class, () -> analysis(List.of(onlyGoal),
                Set.of(UUID.randomUUID())));

        var first = goal("重复目标");
        var second = goal("重复目标");
        assertThrows(IllegalArgumentException.class, () -> analysis(List.of(first, second),
                Set.of(first.id(), second.id())));
    }

    private RequestAnalysis analysis(List<GoalPlan> goals, Set<UUID> mappedGoals) {
        return new RequestAnalysis("独立问题",
                List.of(new ObjectiveRequirement(UUID.randomUUID(), "回答目标", true, mappedGoals)),
                List.of(), goals);
    }

    private GoalPlan goal(String question) {
        var goalId = UUID.randomUUID();
        var requirement = new RequirementPlan(UUID.randomUUID(), goalId, question + "证据面");
        var requirements = Set.of(requirement.id());
        var pair = new QueryPair(goalId, ResearchPhase.PRIMARY,
                new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                        SearchQueryRole.PRIMARY_KEYWORD, question + " 关键词", SearchMode.KEYWORD, requirements),
                new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                        SearchQueryRole.PRIMARY_SEMANTIC, "查找能够回答" + question + "的资料",
                        SearchMode.SEMANTIC, requirements));
        return new GoalPlan(goalId, question, List.of(requirement), pair);
    }
}
