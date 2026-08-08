package com.yanyue.rag.domain.agent.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.EvidenceRequirementLink;
import com.yanyue.rag.domain.agent.v4.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.v4.RequirementPlan;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v7.AgenticV7Limits;
import com.yanyue.rag.domain.agent.v8.AgenticV8Limits;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.SourceAnchor;
import com.yanyue.rag.domain.chunking.v4.SourceAnchorSegment;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoalEvidencePoolTest {
    @Test
    void v7PrecisionProfileKeepsTwoEvidencePerRequirement() {
        var fixture = fixture();
        var limits = AgenticV7Limits.defaults();
        var pool = new GoalEvidencePool(fixture.analysis(), limits);

        pool.accept(evidence(fixture, "第一条直接证据"));
        pool.accept(evidence(fixture, "第二条直接证据"));

        assertEquals(2, pool.size());
        assertThrows(IllegalStateException.class,
                () -> pool.accept(evidence(fixture, "第三条重复证据")));
    }

    @Test
    void storesOnlySupportQuotesThatExistInTheAcceptedParent() {
        var fixture = fixture();
        var pool = new GoalEvidencePool(fixture.analysis(), AgenticV7Limits.defaults());
        var evidence = pool.accept(evidence(fixture, "客户端负责提交构建命令，服务端执行构建。"));

        pool.recordSupportQuotes(evidence.evidenceId(), List.of("客户端负责提交构建命令"));

        assertEquals(List.of("客户端负责提交构建命令"), pool.supportQuotes(evidence.evidenceId()));
        assertThrows(IllegalArgumentException.class,
                () -> pool.recordSupportQuotes(evidence.evidenceId(), List.of("并不存在的原文")));
    }

    @Test
    void v8ExpandedProfileAcceptsSixEvidenceItemsForOneRequirement() {
        var fixture = fixture();
        var pool = new GoalEvidencePool(fixture.analysis(), AgenticV8Limits.defaults());

        for (int index = 1; index <= 6; index++) {
            pool.accept(evidence(fixture, "第" + index + "条直接证据"));
        }

        assertEquals(6, pool.size());
        assertThrows(IllegalStateException.class,
                () -> pool.accept(evidence(fixture, "第七条直接证据")));
    }

    private Fixture fixture() {
        var goalId = UUID.randomUUID();
        var requirement = new RequirementPlan(UUID.randomUUID(), goalId, "部署步骤");
        var targetIds = Set.of(requirement.id());
        var keyword = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.PRIMARY_KEYWORD, "产品 部署 步骤", SearchMode.KEYWORD, targetIds);
        var semantic = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.PRIMARY_SEMANTIC, "应当如何完成产品部署", SearchMode.SEMANTIC, targetIds);
        var goal = new GoalPlan(goalId, "产品应该如何部署", List.of(requirement),
                new QueryPair(goalId, ResearchPhase.PRIMARY, keyword, semantic));
        var objective = new ObjectiveRequirement(UUID.randomUUID(), "说明部署方法", true, Set.of(goalId));
        return new Fixture(new RequestAnalysis("说明产品部署方法", List.of(objective), List.of(), List.of(goal)),
                goal, requirement, keyword.queryId());
    }

    private AcceptedEvidence evidence(Fixture fixture, String quote) {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var parentId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        var segment = new SourceAnchorSegment(blockId, 0, quote.length(), 0, quote.length(), null, null, 1);
        var anchor = new SourceAnchor(versionId, parentId, 0, quote.length(), OffsetUnit.UTF16_CODE_UNIT,
                OffsetUnit.UTF16_CODE_UNIT, null, List.of(segment));
        return new AcceptedEvidence(UUID.randomUUID(), fixture.goal().id(),
                List.of(EvidenceRequirementLink.primary(fixture.requirement().id())), UUID.randomUUID().toString(),
                documentId, versionId, parentId, quote, anchor, "部署", "1", 0.9, ResearchPhase.PRIMARY,
                Set.of(fixture.keywordQueryId()), Set.of(SearchMode.KEYWORD));
    }

    private record Fixture(
            RequestAnalysis analysis,
            GoalPlan goal,
            RequirementPlan requirement,
            UUID keywordQueryId
    ) {
    }
}
