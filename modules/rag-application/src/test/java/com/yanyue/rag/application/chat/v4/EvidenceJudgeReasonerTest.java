package com.yanyue.rag.application.chat.v4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.EvidenceRequirementLink;
import com.yanyue.rag.domain.agent.v4.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v4.GoalStatus;
import com.yanyue.rag.domain.agent.v4.GoalPlan;
import com.yanyue.rag.domain.agent.v4.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.v4.RequirementPlan;
import com.yanyue.rag.domain.agent.v4.RequirementStatus;
import com.yanyue.rag.domain.agent.v4.RequestAnalysis;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v4.SearchQuery;
import com.yanyue.rag.domain.agent.v4.SearchQueryRole;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.SourceAnchor;
import com.yanyue.rag.domain.chunking.v4.SourceAnchorSegment;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceJudgeReasonerTest {
    private final EvidenceJudgeReasoner reasoner = new EvidenceJudgeReasoner(null, new ObjectMapper());

    @Test
    void rejectsEvidenceThatWasNotOfferedToJudge() {
        var fixture = fixture();
        var first = evidence(fixture, "证据一", 0.9);
        var omitted = evidence(fixture, "证据二", 0.8);
        fixture.pool().accept(first);
        fixture.pool().accept(omitted);
        var raw = """
                {"goalDecisions":[{"goalId":"%s","requirementDecisions":[
                  {"requirementId":"%s","status":"COVERED","evidenceIds":["%s"]}
                ],"conflicts":[],"repairQueries":[]}]}
                """.formatted(fixture.goal().id(), fixture.requirement().id(), omitted.evidenceId());

        assertThrows(IllegalStateException.class, () -> reasoner.parse(raw, fixture.analysis(), fixture.pool(),
                Map.of(fixture.goal().id(), Set.of(first.evidenceId()))));
    }

    @Test
    void acceptsExplicitConflictWithTwoOfferedEvidenceItems() {
        var fixture = fixture();
        var first = evidence(fixture, "制度要求启用该功能。", 0.9);
        var second = evidence(fixture, "制度禁止启用该功能。", 0.8);
        fixture.pool().accept(first);
        fixture.pool().accept(second);
        var raw = """
                {"goalDecisions":[{"goalId":"%s","requirementDecisions":[{
                  "requirementId":"%s","status":"CONFLICTING","evidenceIds":["%s","%s"],
                  "repairTarget":{"key":"t1","description":"核实冲突条款","completionMode":"REVIEW_REQUIRED"}
                }],"conflicts":[{"requirementId":"%s","evidenceIds":["%s","%s"]}],
                "repairQueries":[
                  {"role":"REPAIR_KEYWORD","searchMode":"KEYWORD","text":"功能 启用 禁止 条款",
                   "targetRequirementIds":["%s"],"repairTargetKeys":["t1"]},
                  {"role":"REPAIR_SEMANTIC","searchMode":"SEMANTIC","text":"是否允许启用该功能",
                   "targetRequirementIds":["%s"],"repairTargetKeys":["t1"]}
                ]}]}
                """.formatted(fixture.goal().id(), fixture.requirement().id(), first.evidenceId(),
                second.evidenceId(), fixture.requirement().id(), first.evidenceId(), second.evidenceId(),
                fixture.requirement().id(), fixture.requirement().id());

        var decision = reasoner.parse(raw, fixture.analysis(), fixture.pool(),
                Map.of(fixture.goal().id(), Set.of(first.evidenceId(), second.evidenceId())));

        assertEquals(GoalStatus.CONFLICTED, decision.goals().getFirst().goalStatus());
        assertEquals(RequirementStatus.CONFLICTING,
                decision.goals().getFirst().requirements().getFirst().status());
        assertEquals(1, decision.goals().getFirst().conflicts().size());
        assertEquals(2, decision.goals().getFirst().repairQueries().size());
    }

    private Fixture fixture() {
        var goalId = UUID.randomUUID();
        var requirement = new RequirementPlan(UUID.randomUUID(), goalId, "功能启用要求");
        var query = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.INITIAL, "功能 启用 要求", SearchMode.KEYWORD, Set.of(requirement.id()));
        var goal = new GoalPlan(goalId, "该功能是否允许启用", List.of(requirement), query);
        var analysis = new RequestAnalysis("核实功能启用要求", List.of(new ObjectiveRequirement(
                UUID.randomUUID(), "核实启用要求", true, Set.of(goalId))), List.of(), List.of(goal));
        return new Fixture(analysis, goal, requirement, new GoalEvidencePool(analysis));
    }

    private AcceptedEvidence evidence(Fixture fixture, String quote, double score) {
        var versionId = UUID.randomUUID();
        var parentId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        var segment = new SourceAnchorSegment(blockId, 0, quote.length(), 0, quote.length(), 0,
                quote.length(), 1);
        var anchor = new SourceAnchor(versionId, parentId, 0, quote.length(), OffsetUnit.UTF16_CODE_UNIT,
                OffsetUnit.UTF16_CODE_UNIT, OffsetUnit.UTF16_CODE_UNIT, List.of(segment));
        return new AcceptedEvidence(UUID.randomUUID(), fixture.goal().id(),
                List.of(EvidenceRequirementLink.primary(fixture.requirement().id())), UUID.randomUUID().toString(),
                UUID.randomUUID(), versionId, parentId, quote, anchor, "制度 / 功能", "1", score,
                ResearchPhase.PRIMARY, Set.of(fixture.goal().initialQuery().queryId()), Set.of(SearchMode.KEYWORD));
    }

    private record Fixture(
            RequestAnalysis analysis,
            GoalPlan goal,
            RequirementPlan requirement,
            GoalEvidencePool pool
    ) { }
}
