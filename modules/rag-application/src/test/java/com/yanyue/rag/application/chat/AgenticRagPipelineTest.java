package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.EvidenceItem;
import com.yanyue.rag.domain.agent.QuestionPlan;
import com.yanyue.rag.domain.agent.SearchMode;
import com.yanyue.rag.domain.agent.SubQuestion;
import com.yanyue.rag.domain.agent.SubQuestionCoverage;
import com.yanyue.rag.domain.agent.SupportedSurface;
import com.yanyue.rag.domain.model.PipelineConfig;
import com.yanyue.rag.domain.port.AgentRecoveryPort;
import com.yanyue.rag.domain.port.AgentRunArtifactPort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.CitationValidationPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.MemoryFactRepository;
import com.yanyue.rag.domain.port.QueryRewriteModelPort;
import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.port.RetrievalTracePort;
import com.yanyue.rag.domain.port.RunRecordPort;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgenticRagPipelineTest {
    @Test
    void executesTypedQueriesIndependentlyThenDeepReadsAndAlwaysJudgesEvidence() {
        var runId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var chatProfileId = UUID.randomUUID();
        var rewriteProfileId = UUID.randomUUID();
        var rerankProfileId = UUID.randomUUID();
        var now = Instant.parse("2026-07-21T00:00:00Z");
        var config = new PipelineConfig(
                UUID.randomUUID(), organizationId, "test", "fast-rag-v2", "test-prompts",
                chatProfileId, rewriteProfileId, rerankProfileId,
                10, 11, 20, 8, 8, 8_000, 0.2, 30,
                true, now, now);

        var keywordQuestion = new SubQuestion(
                UUID.randomUUID(), "查找精确编号 ZX-100", List.of("编号原文"), 5,
                List.of(), SearchMode.KEYWORD, "找到 ZX-100 对应条款");
        var semanticQuestion = new SubQuestion(
                UUID.randomUUID(), "解释风险复核要求", List.of("流程说明"), 4,
                List.of(), SearchMode.SEMANTIC, "找到风险复核的完整要求");
        var plan = new QuestionPlan(runId, "综合制度要求", List.of(keywordQuestion, semanticQuestion));

        var keywordChildId = UUID.randomUUID();
        var semanticChildId = UUID.randomUUID();
        var keywordParentId = UUID.randomUUID();
        var semanticParentId = UUID.randomUUID();
        var keywordVersionId = UUID.randomUUID();
        var semanticVersionId = UUID.randomUUID();
        var keywordDocumentId = UUID.randomUUID();
        var semanticDocumentId = UUID.randomUUID();
        var keywordChild = hit(keywordChildId, keywordParentId, keywordDocumentId, keywordVersionId,
                "ZX-100", 0.8, List.of("keyword"), 110, 116);
        var semanticChild = hit(semanticChildId, semanticParentId, semanticDocumentId, semanticVersionId,
                "需要进行风险复核", 0.82, List.of("semantic"), 210, 219);
        var keywordParent = hit(keywordParentId, null, keywordDocumentId, keywordVersionId,
                "导言\n制度规定：编号ZX-100必须备案。\n附则", 0.9,
                List.of("keyword", "parent-expand"), 1_000, 1_024);
        var semanticParent = hit(semanticParentId, null, semanticDocumentId, semanticVersionId,
                "流程说明：高风险申请应由两名审核人独立复核后提交。", 0.91,
                List.of("semantic", "parent-expand"), 2_000, 2_026);

        var retrieval = mock(RetrievalPort.class);
        when(retrieval.keywordSearch(eq(keywordQuestion.question()), any(), eq(config.keywordTopK())))
                .thenReturn(List.of(keywordChild));
        when(retrieval.semanticSearch(eq(semanticQuestion.question()), any(), eq(config.semanticTopK()), eq(4)))
                .thenReturn(List.of(semanticChild));
        when(retrieval.expandContext(anyList(), eq(3))).thenAnswer(invocation -> {
            List<RetrievalHit> hits = invocation.getArgument(0);
            return hits.getFirst().chunkId().equals(keywordChildId)
                    ? List.of(keywordParent) : List.of(semanticParent);
        });

        var rerank = mock(RerankModelPort.class);
        when(rerank.rerank(eq(rerankProfileId), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModelPort.RerankScore(0, 0.95)));

        var reasoner = mock(AgentStructuredReasoner.class);
        when(reasoner.plan(chatProfileId, runId, "综合制度要求", 6)).thenReturn(plan);
        when(reasoner.extractEvidenceSpans(eq(chatProfileId), anyString(), anyList()))
                .thenAnswer(invocation -> {
                    List<AgentStructuredReasoner.EvidenceContext> contexts = invocation.getArgument(2);
                    var context = contexts.getFirst();
                    var quote = context.text().contains("ZX-100")
                            ? "编号ZX-100必须备案"
                            : "高风险申请应由两名审核人独立复核后提交";
                    var start = context.text().indexOf(quote);
                    return List.of(new AgentStructuredReasoner.EvidenceSpan(
                            context.key(), quote, start, start + quote.length()));
                });
        when(reasoner.extractEvidenceSpansBatch(eq(chatProfileId), anyList()))
                .thenAnswer(invocation -> {
                    List<AgentStructuredReasoner.EvidenceRequest> requests = invocation.getArgument(1);
                    return requests.stream().flatMap(request -> request.contexts().stream()).map(context -> {
                        var quote = context.text().contains("ZX-100")
                                ? "编号ZX-100必须备案"
                                : "高风险申请应由两名审核人独立复核后提交";
                        var start = context.text().indexOf(quote);
                        return new AgentStructuredReasoner.EvidenceSpan(
                                context.key(), quote, start, start + quote.length());
                    }).toList();
                });
        when(reasoner.evidenceCoverage(eq(chatProfileId), eq(runId), eq(plan), anyList()))
                .thenReturn(new CoverageReport(runId, List.of(
                        new SubQuestionCoverage(keywordQuestion.id(), true, 1, List.of(), false),
                        new SubQuestionCoverage(semanticQuestion.id(), true, 1, List.of(), false))));

        var pipelineConfigs = mock(PipelineConfigService.class);
        when(pipelineConfigs.resolve(organizationId, null)).thenReturn(config);
        var memory = mock(ConversationMemoryPort.class);
        when(memory.recentMessages(conversationId, config.recentTurns())).thenReturn(List.of());
        var metadataSchemas = mock(MetadataSchemaService.class);
        when(metadataSchemas.validateFilters(organizationId, List.of(), List.of())).thenReturn(List.of());
        var artifacts = mock(AgentRunArtifactPort.class);
        var events = mock(RunEventHub.class);
        var runRecords = mock(RunRecordPort.class);

        var pipeline = new AgenticRagPipeline(
                mock(ReactAgentEngine.class), retrieval, mock(RetrievalTracePort.class), rerank,
                mock(QueryRewriteModelPort.class), reasoner, mock(StreamingAnswerModelPort.class),
                pipelineConfigs, memory, mock(MemoryFactRepository.class), artifacts,
                mock(CitationPort.class), mock(CitationValidationPort.class), runRecords, events,
                Runnable::run, Clock.fixed(now, ZoneOffset.UTC), metadataSchemas, RagTelemetry.noop());
        var request = new CreateRunRequest(
                "综合制度要求", RunMode.DEEP, KnowledgeScope.all(), List.of(), null);

        var answer = pipeline.executeRetrievalOnly(
                runId, conversationId, organizationId, userId, request);

        assertEquals("", answer);
        verify(retrieval).keywordSearch(eq(keywordQuestion.question()),
                org.mockito.ArgumentMatchers.any(), eq(config.keywordTopK()));
        verify(retrieval, never()).semanticSearch(eq(keywordQuestion.question()), any(), anyInt(), anyInt());
        verify(retrieval).semanticSearch(eq(semanticQuestion.question()),
                org.mockito.ArgumentMatchers.any(), eq(config.semanticTopK()), eq(4));
        verify(retrieval, never()).keywordSearch(eq(semanticQuestion.question()), any(), anyInt());
        verify(rerank).rerank(eq(rerankProfileId), eq(keywordQuestion.question()), anyList(), eq(1));
        verify(rerank).rerank(eq(rerankProfileId), eq(semanticQuestion.question()), anyList(), eq(1));
        verify(reasoner).evidenceCoverage(eq(chatProfileId), eq(runId), eq(plan), anyList());
        verify(events).publish(eq(runId), eq(StreamEventType.EVIDENCE_JUDGE_STARTED), any());
        verify(events).publish(eq(runId), eq(StreamEventType.EVIDENCE_JUDGE_COMPLETED), any());

        var evidenceCaptor = ArgumentCaptor.forClass(EvidenceItem.class);
        verify(artifacts, org.mockito.Mockito.times(2)).saveEvidence(eq(runId), evidenceCaptor.capture());
        var evidence = evidenceCaptor.getAllValues();
        var keywordEvidence = evidence.stream()
                .filter(item -> item.subQuestionId().equals(keywordQuestion.id())).findFirst().orElseThrow();
        var semanticEvidence = evidence.stream()
                .filter(item -> item.subQuestionId().equals(semanticQuestion.id())).findFirst().orElseThrow();
        assertEquals(1_000 + keywordParent.text().indexOf(keywordEvidence.quote()), keywordEvidence.sourceStart());
        assertEquals(keywordEvidence.sourceStart() + keywordEvidence.quote().length(), keywordEvidence.sourceEnd());
        assertEquals(2_000 + semanticParent.text().indexOf(semanticEvidence.quote()), semanticEvidence.sourceStart());
        assertTrue(keywordEvidence.retrievalSources().contains("evidence-span"));
    }

    @Test
    void contextExpansionFailureIsRecordedAndStillReachesMandatoryEvidenceJudge() {
        var runId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var chatProfileId = UUID.randomUUID();
        var now = Instant.parse("2026-07-21T00:00:00Z");
        var config = new PipelineConfig(
                UUID.randomUUID(), organizationId, "test", "fast-rag-v2", "test-prompts",
                chatProfileId, UUID.randomUUID(), UUID.randomUUID(),
                10, 10, 20, 8, 8, 8_000, 0.2, 30,
                true, now, now);
        var question = new SubQuestion(
                UUID.randomUUID(), "查找制度编号 ZX-100", List.of("制度原文"), 5,
                List.of(), SearchMode.KEYWORD, "找到编号对应的完整条款");
        var plan = new QuestionPlan(runId, "查找制度编号 ZX-100", List.of(question));
        var child = hit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ZX-100", 0.9, List.of("keyword"), 0, 6);
        var gapQuery = new AgentStructuredReasoner.GapQuery(
                question.id(), "ZX-100 完整条款 原文", SearchMode.KEYWORD);

        var retrieval = mock(RetrievalPort.class);
        when(retrieval.keywordSearch(eq(question.question()), any(), eq(config.keywordTopK())))
                .thenReturn(List.of(child));
        when(retrieval.keywordSearch(eq(gapQuery.query()), any(), eq(config.keywordTopK())))
                .thenReturn(List.of(child));
        when(retrieval.expandContext(anyList(), eq(3)))
                .thenThrow(new IllegalStateException("parent context unavailable"));
        var rerank = mock(RerankModelPort.class);
        when(rerank.rerank(eq(config.rerankProfileId()), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModelPort.RerankScore(0, 0.95)));
        var reasoner = mock(AgentStructuredReasoner.class);
        when(reasoner.plan(chatProfileId, runId, plan.objective(), 6)).thenReturn(plan);
        when(reasoner.evidenceCoverage(eq(chatProfileId), eq(runId), eq(plan), anyList()))
                .thenReturn(new CoverageReport(runId, List.of(
                        new SubQuestionCoverage(question.id(), false, 0,
                                List.of("缺少编号对应的完整条款"), false))));
        when(reasoner.gapQueries(eq(chatProfileId), eq(plan), any(), anyList()))
                .thenReturn(List.of(gapQuery));

        var pipelineConfigs = mock(PipelineConfigService.class);
        when(pipelineConfigs.resolve(organizationId, null)).thenReturn(config);
        var memory = mock(ConversationMemoryPort.class);
        when(memory.recentMessages(conversationId, config.recentTurns())).thenReturn(List.of());
        var metadataSchemas = mock(MetadataSchemaService.class);
        when(metadataSchemas.validateFilters(organizationId, List.of(), List.of())).thenReturn(List.of());
        var artifacts = mock(AgentRunArtifactPort.class);
        var events = mock(RunEventHub.class);

        var pipeline = new AgenticRagPipeline(
                mock(ReactAgentEngine.class), retrieval, mock(RetrievalTracePort.class), rerank,
                mock(QueryRewriteModelPort.class), reasoner, mock(StreamingAnswerModelPort.class),
                pipelineConfigs, memory, mock(MemoryFactRepository.class), artifacts,
                mock(CitationPort.class), mock(CitationValidationPort.class), mock(RunRecordPort.class), events,
                Runnable::run, Clock.fixed(now, ZoneOffset.UTC), metadataSchemas, RagTelemetry.noop());

        assertEquals("", pipeline.executeRetrievalOnly(
                runId, conversationId, organizationId, userId,
                new CreateRunRequest(plan.objective(), RunMode.DEEP, KnowledgeScope.all(), List.of(), null)));
        verify(events, org.mockito.Mockito.times(2))
                .publish(eq(runId), eq(StreamEventType.DEEP_READ_FAILED), any());
        verify(events, org.mockito.Mockito.times(2))
                .publish(eq(runId), eq(StreamEventType.EVIDENCE_JUDGE_STARTED), any());
        verify(events, org.mockito.Mockito.times(2))
                .publish(eq(runId), eq(StreamEventType.EVIDENCE_JUDGE_COMPLETED), any());
        verify(events).publish(eq(runId), eq(StreamEventType.GAP_QUERY_CREATED), any());
        verify(retrieval).keywordSearch(eq(gapQuery.query()), any(), eq(config.keywordTopK()));
        verify(reasoner, org.mockito.Mockito.times(2))
                .evidenceCoverage(eq(chatProfileId), eq(runId), eq(plan), eq(List.of()));
        verify(artifacts, never()).saveEvidence(eq(runId), any());
    }

    @Test
    void retriesTheSameDeepReadAssignmentAfterExtractionTransportFailure() {
        var runId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var chatProfileId = UUID.randomUUID();
        var now = Instant.parse("2026-07-21T00:00:00Z");
        var config = new PipelineConfig(
                UUID.randomUUID(), organizationId, "test", "fast-rag-v2", "test-prompts",
                chatProfileId, UUID.randomUUID(), UUID.randomUUID(),
                10, 10, 20, 8, 8, 8_000, 0.2, 30,
                true, now, now);
        var question = new SubQuestion(
                UUID.randomUUID(), "ZX-100 备案要求", List.of("备案原文"), 5,
                List.of(), SearchMode.KEYWORD, "找到 ZX-100 的完整备案要求");
        var plan = new QuestionPlan(runId, question.question(), List.of(question));
        var child = hit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ZX-100", 0.9, List.of("keyword"), 10, 16);
        var parent = hit(child.parentChunkId(), null, child.documentId(), child.documentVersionId(),
                "制度规定：编号 ZX-100 必须备案。", 0.95,
                List.of("keyword", "parent-expand"), 1_000, 1_020);
        var gapQuery = new AgentStructuredReasoner.GapQuery(
                question.id(), "ZX-100 完整备案原文", SearchMode.KEYWORD);

        var retrieval = mock(RetrievalPort.class);
        when(retrieval.keywordSearch(eq(question.question()), any(), eq(config.keywordTopK())))
                .thenReturn(List.of(child));
        when(retrieval.keywordSearch(eq(gapQuery.query()), any(), eq(config.keywordTopK())))
                .thenReturn(List.of(child));
        when(retrieval.expandContext(anyList(), eq(3))).thenReturn(List.of(parent));
        var rerank = mock(RerankModelPort.class);
        when(rerank.rerank(eq(config.rerankProfileId()), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModelPort.RerankScore(0, 0.95)));

        var reasoner = mock(AgentStructuredReasoner.class);
        when(reasoner.plan(chatProfileId, runId, plan.objective(), 6)).thenReturn(plan);
        when(reasoner.extractEvidenceSpansBatch(eq(chatProfileId), anyList()))
                .thenThrow(new IllegalStateException("provider timeout"))
                .thenAnswer(invocation -> {
                    List<AgentStructuredReasoner.EvidenceRequest> requests = invocation.getArgument(1);
                    var context = requests.getFirst().contexts().getFirst();
                    var quote = "编号 ZX-100 必须备案";
                    var start = context.text().indexOf(quote);
                    return List.of(new AgentStructuredReasoner.EvidenceSpan(
                            context.key(), quote, start, start + quote.length()));
                });
        when(reasoner.evidenceCoverage(eq(chatProfileId), eq(runId), eq(plan), anyList()))
                .thenReturn(
                        new CoverageReport(runId, List.of(new SubQuestionCoverage(
                                question.id(), false, 0, List.of("缺少备案原文"), false))),
                        new CoverageReport(runId, List.of(new SubQuestionCoverage(
                                question.id(), true, 1, List.of(), false))));
        when(reasoner.gapQueries(eq(chatProfileId), eq(plan), any(), anyList()))
                .thenReturn(List.of(gapQuery));

        var pipelineConfigs = mock(PipelineConfigService.class);
        when(pipelineConfigs.resolve(organizationId, null)).thenReturn(config);
        var memory = mock(ConversationMemoryPort.class);
        when(memory.recentMessages(conversationId, config.recentTurns())).thenReturn(List.of());
        var metadataSchemas = mock(MetadataSchemaService.class);
        when(metadataSchemas.validateFilters(organizationId, List.of(), List.of())).thenReturn(List.of());
        var artifacts = mock(AgentRunArtifactPort.class);
        var events = mock(RunEventHub.class);

        var pipeline = new AgenticRagPipeline(
                mock(ReactAgentEngine.class), retrieval, mock(RetrievalTracePort.class), rerank,
                mock(QueryRewriteModelPort.class), reasoner, mock(StreamingAnswerModelPort.class),
                pipelineConfigs, memory, mock(MemoryFactRepository.class), artifacts,
                mock(CitationPort.class), mock(CitationValidationPort.class), mock(RunRecordPort.class), events,
                Runnable::run, Clock.fixed(now, ZoneOffset.UTC), metadataSchemas, RagTelemetry.noop());

        assertEquals("", pipeline.executeRetrievalOnly(
                runId, conversationId, organizationId, userId,
                new CreateRunRequest(plan.objective(), RunMode.DEEP, KnowledgeScope.all(), List.of(), null)));
        verify(reasoner, org.mockito.Mockito.times(2))
                .extractEvidenceSpansBatch(eq(chatProfileId), anyList());
        verify(retrieval, org.mockito.Mockito.times(2)).expandContext(anyList(), eq(3));
        verify(artifacts).saveEvidence(eq(runId), any());
        verify(events).publish(eq(runId), eq(StreamEventType.DEEP_READ_FAILED), any());
        verify(events).publish(eq(runId), eq(StreamEventType.DEEP_READ_COMPLETED), any());
    }

    @Test
    void returnsGroundedPartialAnswerAfterJudgeGapsWhenEveryQuestionHasEvidence() {
        var runId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var chatProfileId = UUID.randomUUID();
        var now = Instant.parse("2026-07-21T00:00:00Z");
        var config = new PipelineConfig(
                UUID.randomUUID(), organizationId, "test", "fast-rag-v2", "test-prompts",
                chatProfileId, UUID.randomUUID(), UUID.randomUUID(),
                10, 10, 20, 8, 8, 8_000, 0.2, 30,
                true, now, now);
        var question = new SubQuestion(
                UUID.randomUUID(), "ZX-100 备案要求", List.of("备案原文"), 5,
                List.of(), SearchMode.KEYWORD, "核验备案要求及例外");
        var plan = new QuestionPlan(runId, question.question(), List.of(question));
        var child = hit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ZX-100", 0.9, List.of("keyword"), 10, 16);
        var parent = hit(child.parentChunkId(), null, child.documentId(), child.documentVersionId(),
                "制度规定：编号 ZX-100 必须备案。", 0.95,
                List.of("keyword", "parent-expand"), 1_000, 1_020);

        var retrieval = mock(RetrievalPort.class);
        when(retrieval.keywordSearch(eq(question.question()), any(), eq(config.keywordTopK())))
                .thenReturn(List.of(child));
        when(retrieval.expandContext(anyList(), eq(3))).thenReturn(List.of(parent));
        var rerank = mock(RerankModelPort.class);
        when(rerank.rerank(eq(config.rerankProfileId()), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModelPort.RerankScore(0, 0.95)));
        var reasoner = mock(AgentStructuredReasoner.class);
        when(reasoner.plan(chatProfileId, runId, plan.objective(), 6)).thenReturn(plan);
        when(reasoner.extractEvidenceSpansBatch(eq(chatProfileId), anyList()))
                .thenAnswer(invocation -> {
                    List<AgentStructuredReasoner.EvidenceRequest> requests = invocation.getArgument(1);
                    var context = requests.getFirst().contexts().getFirst();
                    var quote = "编号 ZX-100 必须备案";
                    var start = context.text().indexOf(quote);
                    return List.of(new AgentStructuredReasoner.EvidenceSpan(
                            context.key(), quote, start, start + quote.length()));
                });
        when(reasoner.evidenceCoverage(eq(chatProfileId), eq(runId), eq(plan), anyList()))
                .thenAnswer(invocation -> {
                    List<EvidenceItem> currentEvidence = invocation.getArgument(3);
                    return new CoverageReport(runId, List.of(new SubQuestionCoverage(
                            question.id(), false, 1, List.of("缺少备案例外条款"), false,
                            List.of(new SupportedSurface(
                                    "ZX-100 必须备案", List.of(currentEvidence.getFirst().id()))))));
                });
        when(reasoner.gapQueries(eq(chatProfileId), eq(plan), any(), anyList())).thenReturn(List.of());

        var answerModel = mock(StreamingAnswerModelPort.class);
        when(answerModel.generate(eq(chatProfileId), any(), any())).thenReturn(
                new StreamingAnswerModelPort.GenerationResult("ZX-100 必须备案 [E1]", 20, 8, "stop"));
        var citationValidation = mock(CitationValidationPort.class);
        when(citationValidation.isCurrentlyValid(
                eq(organizationId), eq(userId), any(), eq(now))).thenReturn(true);
        var pipelineConfigs = mock(PipelineConfigService.class);
        when(pipelineConfigs.resolve(organizationId, null)).thenReturn(config);
        var memory = mock(ConversationMemoryPort.class);
        when(memory.recentMessages(conversationId, config.recentTurns())).thenReturn(List.of());
        var metadataSchemas = mock(MetadataSchemaService.class);
        when(metadataSchemas.validateFilters(organizationId, List.of(), List.of())).thenReturn(List.of());
        var artifacts = mock(AgentRunArtifactPort.class);
        var events = mock(RunEventHub.class);
        var runRecords = mock(RunRecordPort.class);

        var pipeline = new AgenticRagPipeline(
                mock(ReactAgentEngine.class), retrieval, mock(RetrievalTracePort.class), rerank,
                mock(QueryRewriteModelPort.class), reasoner, answerModel,
                pipelineConfigs, memory, mock(MemoryFactRepository.class), artifacts,
                mock(CitationPort.class), citationValidation, runRecords, events,
                Runnable::run, Clock.fixed(now, ZoneOffset.UTC), metadataSchemas, RagTelemetry.noop());

        var answer = pipeline.execute(
                runId, conversationId, organizationId, userId,
                new CreateRunRequest(plan.objective(), RunMode.DEEP, KnowledgeScope.all(), List.of(), null));

        assertTrue(answer.contains("ZX-100 必须备案 [E1]"));
        assertTrue(answer.contains("缺少备案例外条款"));
        verify(events).publish(eq(runId), eq(StreamEventType.PARTIAL_ANSWER), any());
        verify(runRecords, never()).markNoAnswer(any(), anyString());
        verify(answerModel, never()).generate(any(), any(), any());
    }

    private RetrievalHit hit(
            UUID chunkId,
            UUID parentChunkId,
            UUID documentId,
            UUID documentVersionId,
            String text,
            double score,
            List<String> sources,
            int sourceStart,
            int sourceEnd
    ) {
        return new RetrievalHit(
                chunkId, parentChunkId, documentId, documentVersionId, "制度文档", text,
                score, sources, 1, sourceStart, sourceEnd);
    }
}
