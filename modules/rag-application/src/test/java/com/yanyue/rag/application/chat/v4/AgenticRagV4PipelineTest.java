package com.yanyue.rag.application.chat.v4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.EvidenceRequirementLink;
import com.yanyue.rag.domain.agent.v4.GoalPlan;
import com.yanyue.rag.domain.agent.v4.GoalStatus;
import com.yanyue.rag.domain.agent.v4.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.v4.RepairCompletionMode;
import com.yanyue.rag.domain.agent.v4.RepairTarget;
import com.yanyue.rag.domain.agent.v4.RequirementPlan;
import com.yanyue.rag.domain.agent.v4.RequirementStatus;
import com.yanyue.rag.domain.agent.v4.RequestAnalysis;
import com.yanyue.rag.domain.agent.v4.ResearchHealth;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v4.SearchQuery;
import com.yanyue.rag.domain.agent.v4.SearchQueryRole;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.SourceAnchor;
import com.yanyue.rag.domain.chunking.v4.SourceAnchorSegment;
import com.yanyue.rag.domain.model.PipelineConfig;
import com.yanyue.rag.domain.port.AgenticV4ArtifactPort;
import com.yanyue.rag.domain.port.AgenticV4EvidenceValidationPort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.CitationValidationPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.RunRecordPort;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgenticRagV4PipelineTest {
    private final UUID runId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID rerankProfileId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-07-23T08:00:00Z");

    private PipelineConfigService configs;
    private MetadataSchemaService metadata;
    private ConversationMemoryPort memory;
    private RequestAnalysisReasoner planner;
    private GoalResearchService research;
    private EvidenceJudgeReasoner judge;
    private StreamingAnswerModelPort answerModel;
    private CitationValidationPort citationValidation;
    private AgenticV4EvidenceValidationPort evidenceValidation;
    private AgenticV4ArtifactPort artifacts;
    private RunRecordPort runs;
    private RunEventHub events;

    @BeforeEach
    void setUp() {
        configs = mock(PipelineConfigService.class);
        metadata = mock(MetadataSchemaService.class);
        memory = mock(ConversationMemoryPort.class);
        planner = mock(RequestAnalysisReasoner.class);
        research = mock(GoalResearchService.class);
        judge = mock(EvidenceJudgeReasoner.class);
        answerModel = mock(StreamingAnswerModelPort.class);
        citationValidation = mock(CitationValidationPort.class);
        evidenceValidation = mock(AgenticV4EvidenceValidationPort.class);
        artifacts = mock(AgenticV4ArtifactPort.class);
        runs = mock(RunRecordPort.class);
        events = mock(RunEventHub.class);
        when(configs.resolve(organizationId, null)).thenReturn(config());
        when(metadata.validateFilters(eq(organizationId), anyList(), anyList())).thenReturn(List.of());
        when(memory.recentMessages(conversationId, 3)).thenReturn(List.of());
        when(runs.isCancellationRequested(runId)).thenReturn(false);
        when(artifacts.claimModelAttempt(any())).thenReturn(true);
        when(evidenceValidation.isCurrentlyValid(eq(organizationId), eq(userId), any(), eq(now))).thenReturn(true);
    }

    @Test
    void performsExactlyOneJudgeAndOneRepairPhaseThenReturnsNoEvidence() {
        var fixture = fixture();
        when(planner.analyze(eq(profileId), eq(runId), anyString(), anyList(), any()))
                .thenReturn(fixture.analysis());
        when(research.research(any(), any(), eq(runId), anyString(), eq(fixture.goal()),
                any(), anyList(), anyList(), any(), anyInt(), anyInt(), anyInt(), any(Double.class), any(), any()))
                .thenAnswer(invocation -> new GoalResearchService.ResearchResult(
                        fixture.goal().id(), invocation.getArgument(5), List.of(),
                        ResearchHealth.COMPLETED_EMPTY, false));
        when(judge.judge(eq(profileId), eq(runId), eq(fixture.analysis()), any(), any()))
                .thenReturn(fixture.missingDecision());

        var answer = pipeline().execute(runId, conversationId, organizationId, userId,
                request("说明部署要求"), true);

        assertEquals(AgenticRagV4Pipeline.NO_EVIDENCE_MESSAGE, answer);
        verify(judge, times(1)).judge(eq(profileId), eq(runId), eq(fixture.analysis()), any(), any());
        verify(research, times(2)).research(any(), any(), eq(runId), anyString(), eq(fixture.goal()),
                any(), anyList(), anyList(), any(), anyInt(), anyInt(), anyInt(), any(Double.class), any(), any());
        verify(answerModel, never()).generate(any(), any(), any(), anyInt());
        verify(runs).markAnswerMode(runId, "NO_EVIDENCE", "ZERO_ACCEPTED_EVIDENCE");
    }

    @Test
    void answersFromAcceptedEvidenceWithoutDisclosingMissingRequirements() {
        var fixture = fixture();
        var evidence = evidence(fixture.goal(), fixture.requirement());
        when(planner.analyze(eq(profileId), eq(runId), anyString(), anyList(), any()))
                .thenReturn(fixture.analysis());
        when(research.research(any(), any(), eq(runId), anyString(), eq(fixture.goal()),
                eq(ResearchPhase.PRIMARY), anyList(), anyList(), any(), anyInt(), anyInt(), anyInt(),
                any(Double.class), any(), any())).thenAnswer(invocation -> {
                    var pool = invocation.<com.yanyue.rag.domain.agent.v4.GoalEvidencePool>getArgument(14);
                    pool.accept(evidence);
                    return new GoalResearchService.ResearchResult(fixture.goal().id(), ResearchPhase.PRIMARY,
                            List.of(evidence), ResearchHealth.COMPLETED_WITH_EVIDENCE, false);
                });
        var covered = new EvidenceJudgeReasoner.RequirementDecision(
                fixture.requirement().id(), RequirementStatus.COVERED, Set.of(evidence.evidenceId()), null);
        var decision = new EvidenceJudgeReasoner.JudgeDecision(List.of(
                new EvidenceJudgeReasoner.GoalDecision(fixture.goal().id(), List.of(covered), List.of(),
                        List.of(), GoalStatus.SATISFIED_LOCKED)), false);
        when(judge.judge(eq(profileId), eq(runId), eq(fixture.analysis()), any(), any())).thenReturn(decision);
        when(answerModel.generate(eq(profileId), any(), any(), eq(1))).thenReturn(
                new StreamingAnswerModelPort.GenerationResult("该组件必须备案。[E1]", 120, 20, "stop"));
        when(citationValidation.isCurrentlyValid(eq(organizationId), eq(userId), any(), eq(now)))
                .thenReturn(true);

        var answer = pipeline().execute(runId, conversationId, organizationId, userId,
                request("说明部署要求"), true);

        assertEquals("该组件必须备案。[E1]", answer);
        var requestCaptor = ArgumentCaptor.forClass(StreamingAnswerModelPort.AnswerRequest.class);
        verify(answerModel).generate(eq(profileId), requestCaptor.capture(), any(), eq(1));
        assertEquals(List.of("制度规定：该组件必须备案。"),
                requestCaptor.getValue().evidence().stream().map(StreamingAnswerModelPort.AnswerEvidence::text).toList());
        verify(research, times(1)).research(any(), any(), eq(runId), anyString(), eq(fixture.goal()),
                any(), anyList(), anyList(), any(), anyInt(), anyInt(), anyInt(), any(Double.class), any(), any());
        verify(runs).markAnswerMode(runId, "ANSWER_WITH_EVIDENCE", "COMPLETED_WITH_EVIDENCE");
    }

    private AgenticRagV4Pipeline pipeline() {
        return new AgenticRagV4Pipeline(configs, metadata, memory, planner, research, judge,
                new CoverageStateReducer(), answerModel, mock(CitationPort.class), citationValidation,
                evidenceValidation, artifacts, runs, events, Runnable::run,
                Clock.fixed(now, ZoneOffset.UTC), new ObjectMapper());
    }

    private Fixture fixture() {
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var requirement = new RequirementPlan(requirementId, goalId, "备案要求");
        var initial = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.INITIAL, "组件 备案 要求", SearchMode.KEYWORD, Set.of(requirementId));
        var goal = new GoalPlan(goalId, "组件有哪些备案要求", List.of(requirement), initial);
        var analysis = new RequestAnalysis("说明组件备案要求", List.of(new ObjectiveRequirement(
                UUID.randomUUID(), "说明备案要求", true, Set.of(goalId))), List.of(), List.of(goal));
        var target = RepairTarget.open(UUID.randomUUID(), goalId, requirementId, "完整备案要求",
                RepairCompletionMode.SINGLE_SPAN_COMPLETABLE);
        var keyword = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.REPAIR,
                SearchQueryRole.REPAIR_KEYWORD, "组件 备案 完整 条款", SearchMode.KEYWORD, Set.of(requirementId));
        var semantic = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.REPAIR,
                SearchQueryRole.REPAIR_SEMANTIC, "该组件需要满足哪些完整备案条件",
                SearchMode.SEMANTIC, Set.of(requirementId));
        var missing = new EvidenceJudgeReasoner.RequirementDecision(
                requirementId, RequirementStatus.MISSING, Set.of(), target);
        var decision = new EvidenceJudgeReasoner.JudgeDecision(List.of(
                new EvidenceJudgeReasoner.GoalDecision(goalId, List.of(missing), List.of(target),
                        List.of(keyword, semantic), GoalStatus.NEEDS_REPAIR)), false);
        return new Fixture(analysis, goal, requirement, decision);
    }

    private AcceptedEvidence evidence(GoalPlan goal, RequirementPlan requirement) {
        var versionId = UUID.randomUUID();
        var parentId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        var quote = "制度规定：该组件必须备案。";
        var segment = new SourceAnchorSegment(blockId, 0, quote.length(), 0, quote.length(),
                100, 100 + quote.length(), 1);
        var anchor = new SourceAnchor(versionId, parentId, 0, quote.length(), OffsetUnit.UTF16_CODE_UNIT,
                OffsetUnit.UTF16_CODE_UNIT, OffsetUnit.UTF16_CODE_UNIT, List.of(segment));
        return new AcceptedEvidence(UUID.randomUUID(), goal.id(),
                List.of(EvidenceRequirementLink.primary(requirement.id())), "a".repeat(64),
                UUID.randomUUID(), versionId, parentId, quote, anchor, "部署 / 备案", "1", 0.95,
                ResearchPhase.PRIMARY, Set.of(goal.initialQuery().queryId()), Set.of(SearchMode.KEYWORD));
    }

    private PipelineConfig config() {
        return new PipelineConfig(UUID.randomUUID(), organizationId, "test", "agentic-rag-v4", "v4",
                profileId, UUID.randomUUID(), rerankProfileId, 10, 10, 20, 8, 4, 8_000,
                0.2, 30, true, now, now);
    }

    private CreateRunRequest request(String query) {
        return new CreateRunRequest(query, RunMode.DEEP, KnowledgeScope.all(), List.of(), null);
    }

    private record Fixture(
            RequestAnalysis analysis,
            GoalPlan goal,
            RequirementPlan requirement,
            EvidenceJudgeReasoner.JudgeDecision missingDecision
    ) { }
}
