package com.yanyue.rag.domain.agent.v4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.SourceAnchor;
import com.yanyue.rag.domain.chunking.v4.SourceAnchorSegment;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoalEvidencePoolTest {
    @Test
    void deduplicatesSameSpanWithinGoalAndKeepsCrossGoalOwnershipSeparate() {
        var fixture = fixture(2);
        var pool = new GoalEvidencePool(fixture.analysis());
        var first = evidence(fixture.goals().getFirst(), "证据一", UUID.randomUUID());
        var duplicate = copyForGoal(first, fixture.goals().getFirst(), UUID.randomUUID());
        var otherGoal = copyForGoal(first, fixture.goals().get(1), UUID.randomUUID());

        pool.accept(first);
        assertEquals(first.evidenceId(), pool.accept(duplicate).evidenceId());
        pool.accept(otherGoal);

        assertEquals(2, pool.size());
    }

    @Test
    void enforcesTwoActiveEvidenceLinksPerRequirement() {
        var fixture = fixture(1);
        var pool = new GoalEvidencePool(fixture.analysis());
        var goal = fixture.goals().getFirst();
        pool.accept(evidence(goal, "第一条", UUID.randomUUID()));
        pool.accept(evidence(goal, "第二条", UUID.randomUUID()));

        assertThrows(IllegalStateException.class,
                () -> pool.accept(evidence(goal, "第三条", UUID.randomUUID())));
    }

    @Test
    void rejectsEvidenceLinkedToAnotherGoalsRequirement() {
        var fixture = fixture(2);
        var pool = new GoalEvidencePool(fixture.analysis());
        var sourceGoal = fixture.goals().getFirst();
        var foreignRequirement = fixture.goals().get(1).requirements().getFirst().id();
        var valid = evidence(sourceGoal, "原文", UUID.randomUUID());
        var invalid = new AcceptedEvidence(valid.evidenceId(), sourceGoal.id(),
                List.of(EvidenceRequirementLink.primary(foreignRequirement)), valid.spanId(), valid.documentId(),
                valid.documentVersionId(), valid.parentChunkId(), valid.quote(), valid.sourceAnchor(), "", "",
                valid.retrievalScore(), valid.firstAcceptedPhase(), valid.querySourceIds(), valid.retrievalSources());

        assertThrows(IllegalArgumentException.class, () -> pool.accept(invalid));
    }

    @Test
    void repairCompletionMonotonicallyUpgradesSameSpanLink() {
        var fixture = fixture(1);
        var goal = fixture.goals().getFirst();
        var pool = new GoalEvidencePool(fixture.analysis());
        var primary = evidence(goal, "同一原文", UUID.randomUUID());
        var targetId = UUID.randomUUID();
        var repair = new AcceptedEvidence(UUID.randomUUID(), goal.id(),
                List.of(EvidenceRequirementLink.repair(goal.requirements().getFirst().id(),
                        targetId, TargetEffect.COMPLETE)), primary.spanId(), primary.documentId(),
                primary.documentVersionId(), primary.parentChunkId(), primary.quote(), primary.sourceAnchor(),
                primary.titlePath(), primary.pageRange(), primary.retrievalScore(), ResearchPhase.REPAIR,
                Set.of(UUID.randomUUID()), Set.of(SearchMode.SEMANTIC));

        pool.accept(primary);
        var merged = pool.accept(repair);
        var link = merged.requirementLinks().getFirst();

        assertEquals(ResearchPhase.REPAIR, link.acceptedPhase());
        assertEquals(targetId, link.repairTargetId());
        assertEquals(TargetEffect.COMPLETE, link.targetEffect());
        assertEquals(primary.evidenceId(), merged.evidenceId());
    }

    private Fixture fixture(int goalCount) {
        var goals = java.util.stream.IntStream.range(0, goalCount)
                .mapToObj(index -> goal("目标" + index))
                .toList();
        var objective = new ObjectiveRequirement(UUID.randomUUID(), "回答目标", true,
                goals.stream().map(GoalPlan::id).collect(java.util.stream.Collectors.toSet()));
        return new Fixture(new RequestAnalysis("独立问题", List.of(objective), List.of(), goals), goals);
    }

    private GoalPlan goal(String question) {
        var goalId = UUID.randomUUID();
        var requirement = new RequirementPlan(UUID.randomUUID(), goalId, "证据面");
        var query = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.INITIAL, question, SearchMode.KEYWORD, Set.of(requirement.id()));
        return new GoalPlan(goalId, question, List.of(requirement), query);
    }

    private AcceptedEvidence evidence(GoalPlan goal, String quote, UUID parentChunkId) {
        return evidenceWithSource(goal, quote, UUID.randomUUID(), UUID.randomUUID(), parentChunkId,
                UUID.randomUUID().toString(), UUID.randomUUID());
    }

    private AcceptedEvidence evidenceWithSource(GoalPlan goal, String quote, UUID documentId,
                                                UUID versionId, UUID parentChunkId, String spanId,
                                                UUID evidenceId) {
        var blockId = UUID.randomUUID();
        var segment = new SourceAnchorSegment(blockId, 0, quote.length(), 0, quote.length(), null, null, 1);
        var anchor = new SourceAnchor(versionId, parentChunkId, 0, quote.length(), OffsetUnit.UTF16_CODE_UNIT,
                OffsetUnit.UTF16_CODE_UNIT, null, List.of(segment));
        return new AcceptedEvidence(evidenceId, goal.id(),
                List.of(EvidenceRequirementLink.primary(goal.requirements().getFirst().id())), spanId,
                documentId, versionId, parentChunkId, quote, anchor, "标题", "1", 0.9,
                ResearchPhase.PRIMARY, Set.of(goal.initialQuery().queryId()), Set.of(goal.initialQuery().searchMode()));
    }

    private AcceptedEvidence copyForGoal(AcceptedEvidence source, GoalPlan goal, UUID evidenceId) {
        return new AcceptedEvidence(evidenceId, goal.id(),
                List.of(EvidenceRequirementLink.primary(goal.requirements().getFirst().id())), source.spanId(),
                source.documentId(), source.documentVersionId(), source.parentChunkId(), source.quote(),
                source.sourceAnchor(), source.titlePath(), source.pageRange(), source.retrievalScore(),
                source.firstAcceptedPhase(), Set.of(goal.initialQuery().queryId()), source.retrievalSources());
    }

    private record Fixture(RequestAnalysis analysis, List<GoalPlan> goals) {
    }
}
