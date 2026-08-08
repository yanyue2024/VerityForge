package com.yanyue.rag.application.chat.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.EvidenceRequirementLink;
import com.yanyue.rag.domain.agent.v4.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.v4.RequirementPlan;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v5.GoalPlan;
import com.yanyue.rag.domain.agent.v5.QueryPair;
import com.yanyue.rag.domain.agent.v5.RequestAnalysis;
import com.yanyue.rag.domain.agent.v5.SearchQuery;
import com.yanyue.rag.domain.agent.v5.SearchQueryRole;
import com.yanyue.rag.domain.agent.v8.AgenticV8Limits;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.SourceAnchor;
import com.yanyue.rag.domain.chunking.v4.SourceAnchorSegment;
import com.yanyue.rag.domain.model.AssistantProfile;
import com.yanyue.rag.domain.port.AgenticV4ArtifactPort;
import com.yanyue.rag.domain.port.AgenticV4EvidenceValidationPort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.CitationValidationPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentAnswerServiceV5Test {
    @Test
    void goalBatchedAnswerUsesUniqueParentsGoalMappingAndCompleteSourceRange() {
        var answerModel = mock(StreamingAnswerModelPort.class);
        var citations = mock(CitationPort.class);
        var citationValidation = mock(CitationValidationPort.class);
        var evidenceValidation = mock(AgenticV4EvidenceValidationPort.class);
        var artifacts = mock(AgenticV4ArtifactPort.class);
        var memory = mock(ConversationMemoryPort.class);
        var events = mock(RunEventHub.class);
        var now = Instant.parse("2026-08-07T10:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var service = new AgentAnswerServiceV5(answerModel, citations, citationValidation,
                evidenceValidation, artifacts, memory, events, clock);
        when(evidenceValidation.isCurrentlyValid(any(), any(), any(), any())).thenReturn(true);
        when(citationValidation.isCurrentlyValid(any(), any(), any(), any())).thenReturn(true);
        when(artifacts.claimModelAttempt(any())).thenReturn(true);
        when(answerModel.generate(any(), any(), any(), eq(1))).thenReturn(
                new StreamingAnswerModelPort.GenerationResult(
                        "## 身份来源\n普通用户与服务账号不同。[E1]\n\n## 凭据管理\n服务账号使用令牌。[E2]",
                        1_200, 180, "stop"));

        var first = goal("身份来源是什么", "身份来源");
        var second = goal("凭据如何管理", "凭据管理");
        var analysis = new RequestAnalysis("比较身份来源与凭据管理方式", List.of(
                new ObjectiveRequirement(UUID.randomUUID(), "解释身份来源", true, Set.of(first.goal().id())),
                new ObjectiveRequirement(UUID.randomUUID(), "解释凭据管理", true, Set.of(second.goal().id()))),
                List.of(), List.of(first.goal(), second.goal()));
        var sharedVersion = UUID.randomUUID();
        var sharedParent = UUID.randomUUID();
        var sharedDocument = UUID.randomUUID();
        var sharedQuote = "普通用户由外部系统管理。\n服务账号由 Kubernetes API 管理。";
        var evidence = List.of(
                evidence(sharedDocument, sharedVersion, sharedParent, first, sharedQuote, 0.99, 100, 300),
                evidence(sharedDocument, sharedVersion, sharedParent, second, sharedQuote, 0.98, 100, 300),
                evidence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), second,
                        "服务账号使用短期、自动轮换的投射令牌。", 0.95, 500, 560));
        var limits = AgenticV8Limits.finalProfile();
        var ledger = new AgentBudgetLedger(limits, now);

        service.answer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "请比较两方面", analysis, UUID.randomUUID(), 120, evidence, ledger, limits,
                assistant(), List.of("user: 上一轮问题", "assistant: 上一轮回答"), 0.2);

        var request = ArgumentCaptor.forClass(StreamingAnswerModelPort.AnswerRequest.class);
        verify(answerModel).generate(any(), request.capture(), any(), eq(1));
        assertEquals(2, request.getValue().evidence().size());
        assertEquals(2, request.getValue().evidence().stream()
                .map(StreamingAnswerModelPort.AnswerEvidence::chunkId).distinct().count());
        assertTrue(request.getValue().standaloneQuery().contains("目标 1：身份来源是什么"));
        assertTrue(request.getValue().standaloneQuery().contains("目标 2：凭据如何管理"));
        assertTrue(request.getValue().standaloneQuery().contains("可用证据：[E1]"));
        assertTrue(request.getValue().systemInstruction().contains("每个列出的目标都要回答"));
        assertEquals(4_000, request.getValue().maximumOutputTokens());

        var citation = ArgumentCaptor.forClass(RetrievalHit.class);
        verify(citations, times(2)).save(any(), anyInt(), citation.capture());
        var sharedCitation = citation.getAllValues().stream()
                .filter(value -> value.chunkId().equals(sharedParent)).findFirst().orElseThrow();
        assertEquals(100, sharedCitation.sourceStart());
        assertEquals(300, sharedCitation.sourceEnd());
        assertEquals(sharedQuote, sharedCitation.text());
    }

    private GoalFixture goal(String question, String requirementDescription) {
        var goalId = UUID.randomUUID();
        var requirement = new RequirementPlan(UUID.randomUUID(), goalId, requirementDescription);
        var keyword = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.PRIMARY_KEYWORD, requirementDescription + " 关键词", SearchMode.KEYWORD,
                Set.of(requirement.id()));
        var semantic = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.PRIMARY_SEMANTIC, question + " 的完整说明", SearchMode.SEMANTIC,
                Set.of(requirement.id()));
        return new GoalFixture(new GoalPlan(goalId, question, List.of(requirement),
                new QueryPair(goalId, ResearchPhase.PRIMARY, keyword, semantic)), requirement, keyword.queryId());
    }

    private AcceptedEvidence evidence(
            UUID documentId,
            UUID versionId,
            UUID parentId,
            GoalFixture fixture,
            String quote,
            double score,
            int sourceStart,
            int sourceEnd
    ) {
        int split = Math.max(1, quote.length() / 2);
        var first = new SourceAnchorSegment(UUID.randomUUID(), 0, split, 0, split,
                sourceStart, sourceStart + split, 1);
        var second = new SourceAnchorSegment(UUID.randomUUID(), split, quote.length(), 0,
                quote.length() - split, sourceEnd - (quote.length() - split), sourceEnd, 2);
        var anchor = new SourceAnchor(versionId, parentId, 0, quote.length(), OffsetUnit.UTF16_CODE_UNIT,
                OffsetUnit.UTF16_CODE_UNIT, OffsetUnit.UTF16_CODE_UNIT, List.of(first, second));
        return new AcceptedEvidence(UUID.randomUUID(), fixture.goal().id(),
                List.of(EvidenceRequirementLink.primary(fixture.requirement().id())), "b".repeat(64),
                documentId, versionId, parentId, quote, anchor, "测试文档 / 认证", "1-2", score,
                ResearchPhase.PRIMARY, Set.of(fixture.queryId()),
                Set.of(SearchMode.KEYWORD, SearchMode.SEMANTIC));
    }

    private AssistantProfile assistant() {
        return new AssistantProfile(UUID.randomUUID(), UUID.randomUUID(), 1,
                AssistantProfile.Status.PUBLISHED, "VerityForge", "企业知识助手",
                List.of("完整回答"), "专业、清晰", List.of("不能编造"), "", null, null, null, null);
    }

    private record GoalFixture(GoalPlan goal, RequirementPlan requirement, UUID queryId) {
    }
}
