package com.yanyue.rag.domain.agent.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.domain.agent.deep.RequirementPlan;
import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.SearchMode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryPairTest {
    @Test
    void acceptsOneModeSpecificQueryPerRoute() {
        var fixture = fixture();

        var pair = new QueryPair(fixture.goalId(), ResearchPhase.PRIMARY,
                fixture.keyword(), fixture.semantic());
        var goal = new GoalPlan(fixture.goalId(), "如何部署组件", List.of(fixture.requirement()), pair);

        assertEquals(List.of(fixture.keyword(), fixture.semantic()), goal.primaryQueries());
        assertEquals(fixture.requirementIds(), goal.requirementIds());
    }

    @Test
    void rejectsRoleModeMismatchAndDuplicateNormalizedText() {
        var fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> new SearchQuery(
                UUID.randomUUID(), fixture.goalId(), ResearchPhase.PRIMARY,
                SearchQueryRole.PRIMARY_KEYWORD, "部署步骤", SearchMode.SEMANTIC, fixture.requirementIds()));

        var duplicateSemantic = new SearchQuery(
                UUID.randomUUID(), fixture.goalId(), ResearchPhase.PRIMARY,
                SearchQueryRole.PRIMARY_SEMANTIC, "  部署   步骤  ", SearchMode.SEMANTIC, fixture.requirementIds());
        assertThrows(IllegalArgumentException.class, () -> new QueryPair(
                fixture.goalId(), ResearchPhase.PRIMARY, fixture.keyword(), duplicateSemantic));
    }

    @Test
    void primaryPairMustCoverEveryGoalRequirement() {
        var fixture = fixture();
        var secondRequirement = new RequirementPlan(UUID.randomUUID(), fixture.goalId(), "部署限制");

        assertThrows(IllegalArgumentException.class, () -> new GoalPlan(
                fixture.goalId(), "如何部署组件", List.of(fixture.requirement(), secondRequirement),
                new QueryPair(fixture.goalId(), ResearchPhase.PRIMARY,
                        fixture.keyword(), fixture.semantic())));
    }

    @Test
    void repairPairRequiresRepairRoles() {
        var fixture = fixture();

        assertThrows(IllegalArgumentException.class, () -> new QueryPair(
                fixture.goalId(), ResearchPhase.REPAIR, fixture.keyword(), fixture.semantic()));
    }

    private Fixture fixture() {
        var goalId = UUID.randomUUID();
        var requirement = new RequirementPlan(UUID.randomUUID(), goalId, "部署步骤");
        var requirementIds = Set.of(requirement.id());
        var keyword = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.PRIMARY_KEYWORD, "部署 步骤", SearchMode.KEYWORD, requirementIds);
        var semantic = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.PRIMARY_SEMANTIC, "应该如何完成组件部署", SearchMode.SEMANTIC, requirementIds);
        return new Fixture(goalId, requirement, requirementIds, keyword, semantic);
    }

    private record Fixture(
            UUID goalId,
            RequirementPlan requirement,
            Set<UUID> requirementIds,
            SearchQuery keyword,
            SearchQuery semantic
    ) {
    }
}
