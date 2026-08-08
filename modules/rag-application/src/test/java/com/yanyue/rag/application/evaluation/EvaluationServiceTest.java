package com.yanyue.rag.application.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.application.chat.RunCoordinator;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.RunAcceptedResponse;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.contract.evaluation.StartEvaluationRunRequest;
import com.yanyue.rag.contract.evaluation.EvaluationDatasetBundle;
import com.yanyue.rag.contract.evaluation.EvaluationJudgeMode;
import com.yanyue.rag.domain.evaluation.EvaluationCase;
import com.yanyue.rag.domain.evaluation.EvaluationDataset;
import com.yanyue.rag.domain.evaluation.EvaluationResult;
import com.yanyue.rag.domain.evaluation.EvaluationRun;
import com.yanyue.rag.domain.evaluation.EvaluationRunStatus;
import com.yanyue.rag.domain.evaluation.EvaluationRunLineage;
import com.yanyue.rag.domain.port.EvaluationAttemptPort;
import com.yanyue.rag.domain.port.EvaluationRepository;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class EvaluationServiceTest {
    @Test
    void recognizesNormallyCompletedV4AndV5ResultsAsReusable() {
        var service = new EvaluationService(
                mock(EvaluationRepository.class), emptyRetrieval(), Runnable::run,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var successful = new EvaluationResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Map.of(
                        "execution", "RAG",
                        "selectedMode", "DEEP",
                        "toolFailureCount", 0,
                        "runtimeSnapshot", Map.of("pipelineVersion", "agentic-rag-v4"),
                        "toolDiagnostics", Map.of(
                                "stopReason", "COMPLETED_WITH_EVIDENCE",
                                "failedSupportActionCount", 0,
                                "modelFailedAttemptCount", 0,
                                "judgeCallCount", 1)),
                null, Instant.EPOCH);
        var failedDiagnostics = new EvaluationResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Map.of(
                        "execution", "RAG",
                        "selectedMode", "DEEP",
                        "toolFailureCount", 0,
                        "runtimeSnapshot", Map.of("pipelineVersion", "agentic-rag-v4"),
                        "toolDiagnostics", Map.of(
                                "stopReason", "COMPLETED_WITH_EVIDENCE",
                                "failedSupportActionCount", 1,
                                "modelFailedAttemptCount", 0,
                                "judgeCallCount", 1)),
                null, Instant.EPOCH);
        var successfulV5 = new EvaluationResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Map.of(
                        "execution", "RAG",
                        "selectedMode", "DEEP",
                        "toolFailureCount", 0,
                        "runtimeSnapshot", Map.of("pipelineVersion", "agentic-rag-v5"),
                        "toolDiagnostics", Map.of(
                                "stopReason", "ZERO_ACCEPTED_EVIDENCE",
                                "failedSupportActionCount", 0,
                                "modelFailedAttemptCount", 0,
                                "judgeCallCount", 1)),
                null, Instant.EPOCH);
        var successfulGoalBatchedV8 = new EvaluationResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Map.of(
                        "execution", "RAG",
                        "selectedMode", "DEEP",
                        "toolFailureCount", 0,
                        "runtimeSnapshot", Map.of("pipelineVersion", "agentic-rag-v8"),
                        "toolDiagnostics", Map.of(
                                "stopReason", "COMPLETED_WITH_EVIDENCE",
                                "failedSupportActionCount", 0,
                                "modelFailedLogicalCallCount", 0,
                                "hiddenEvidenceOutcomeCount", 0,
                                "primaryGoalCount", 3,
                                "judgeCallCount", 3)),
                null, Instant.EPOCH);
        var mismatchedGoalBatchedV8 = new EvaluationResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Map.of(
                        "execution", "RAG",
                        "selectedMode", "DEEP",
                        "toolFailureCount", 0,
                        "runtimeSnapshot", Map.of("pipelineVersion", "agentic-rag-v8"),
                        "toolDiagnostics", Map.of(
                                "stopReason", "COMPLETED_WITH_EVIDENCE",
                                "failedSupportActionCount", 0,
                                "modelFailedLogicalCallCount", 0,
                                "hiddenEvidenceOutcomeCount", 0,
                                "primaryGoalCount", 3,
                                "judgeCallCount", 2)),
                null, Instant.EPOCH);

        assertTrue(service.reusableRagResult(successful, "RAG"));
        assertTrue(service.reusableRagResult(successfulV5, "RAG"));
        assertTrue(service.reusableRagResult(successfulGoalBatchedV8, "RAG"));
        assertTrue(!service.reusableRagResult(mismatchedGoalBatchedV8, "RAG"));
        assertTrue(!service.reusableRagResult(failedDiagnostics, "RAG"));
    }

    @Test
    void resumesAFullRagRunByReusingHealthyResultsAndRetryingOnlyFailedCases() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var previousRunId = UUID.randomUUID();
        var nextRunId = UUID.randomUUID();
        var healthyCase = new EvaluationCase(
                UUID.randomUUID(), datasetId, "已成功问题", "答案", List.of(), Map.of());
        var failedCase = new EvaluationCase(
                UUID.randomUUID(), datasetId, "失败问题", "答案", List.of(), Map.of());
        var dataset = new EvaluationDataset(datasetId, organizationId, "增量续跑", "", Instant.EPOCH);
        var request = new StartEvaluationRunRequest(
                RunMode.DEEP, KnowledgeScope.all(), List.of(), null, EvaluationJudgeMode.NONE);
        var snapshot = Map.<String, Object>of(
                "execution", "RAG", "mode", "DEEP", "knowledgeBaseIds", List.of(),
                "documentIds", List.of(), "filters", List.of(), "judgeMode", "NONE");
        var healthyMetrics = Map.<String, Object>of(
                "execution", "RAG", "selectedMode", "DEEP", "toolFailureCount", 0,
                "runtimeSnapshot", Map.of("chatModel", "gpt-5.5"),
                "toolDiagnostics", Map.of(
                        "deepReadFailureCount", 0,
                        "tool.evidence_judge", Map.of("calls", 1, "failed", 0)));
        var previous = new EvaluationRun(
                previousRunId, datasetId, EvaluationRunStatus.COMPLETED,
                Map.of("execution", "RAG", "failedCases", 1), Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
        var next = new EvaluationRun(
                nextRunId, datasetId, EvaluationRunStatus.QUEUED, Map.of(), null, null, Instant.EPOCH);
        var retriedRagRunId = UUID.randomUUID();
        var repository = mock(EvaluationRepository.class);
        when(repository.findRun(organizationId, previousRunId)).thenReturn(Optional.of(previous));
        when(repository.findDataset(organizationId, datasetId)).thenReturn(Optional.of(dataset));
        when(repository.findCases(organizationId, datasetId)).thenReturn(List.of(healthyCase, failedCase));
        when(repository.findResults(organizationId, previousRunId)).thenReturn(List.of(
                new EvaluationResult(UUID.randomUUID(), previousRunId, healthyCase.id(), UUID.randomUUID(),
                        healthyMetrics, null, Instant.EPOCH),
                new EvaluationResult(UUID.randomUUID(), previousRunId, failedCase.id(), UUID.randomUUID(),
                        Map.of("execution", "RAG"), "agent-planner returned HTTP 503", Instant.EPOCH)));
        when(repository.createRun(eq(organizationId), eq(datasetId), any())).thenReturn(next);
        when(repository.createEvaluationConversation(organizationId, userId, nextRunId)).thenReturn(UUID.randomUUID());
        when(repository.findRagRunOutcome(organizationId, retriedRagRunId)).thenReturn(Optional.of(
                new EvaluationRepository.RagRunOutcome(
                        retriedRagRunId, "COMPLETED", "DEEP", "补跑成功", null, Map.of(),
                        0, 0, 0, Instant.EPOCH, Instant.EPOCH.plusMillis(20), null)));
        var attempts = mock(EvaluationAttemptPort.class);
        when(attempts.loadLineage(previousRunId)).thenReturn(Optional.of(
                new EvaluationRunLineage(previousRunId, previousRunId, null, 1, snapshot)));
        when(attempts.loadLineage(nextRunId)).thenReturn(Optional.of(
                new EvaluationRunLineage(nextRunId, previousRunId, previousRunId, 2, snapshot)));
        var coordinator = mock(RunCoordinator.class);
        when(coordinator.start(eq(organizationId), eq(userId), any(), any())).thenReturn(
                new RunAcceptedResponse(retriedRagRunId, RunMode.DEEP, "/events"));
        var service = new EvaluationService(
                repository, emptyRetrieval(), Runnable::run, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                coordinator, mock(EvaluationJudge.class), attempts, 2);

        service.resumeRun(organizationId, userId, previousRunId, null);

        verify(coordinator, times(1)).start(eq(organizationId), eq(userId), any(), any());
        verify(coordinator, times(0)).startAgenticRetrieval(any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        var metrics = ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(repository).saveResult(eq(nextRunId), eq(healthyCase.id()), any(), metrics.capture(), isNull());
        assertEquals(true, metrics.getValue().get("reusedSuccessfulResult"));
        assertEquals(previousRunId, metrics.getValue().get("reusedFromEvaluationRunId"));
        @SuppressWarnings("unchecked")
        var aggregate = ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(repository).completeRun(eq(nextRunId), aggregate.capture());
        assertEquals(1, aggregate.getValue().get("reusedSuccessfulCases"));
        assertEquals(1, aggregate.getValue().get("retriedCases"));
        assertEquals(2, aggregate.getValue().get("successfulCases"));
        assertEquals(0, aggregate.getValue().get("failedCases"));
        verify(attempts).linkResumedRun(nextRunId, previousRunId, snapshot);
    }

    @Test
    void resumesRoutingByKeepingExecutedMisclassificationsAndRetryingOnlyErrors() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var previousRunId = UUID.randomUUID();
        var nextRunId = UUID.randomUUID();
        var misclassifiedCase = new EvaluationCase(
                UUID.randomUUID(), datasetId, "已完成但错判", null, List.of(),
                Map.of("recommendedMode", "DEEP"));
        var failedCase = new EvaluationCase(
                UUID.randomUUID(), datasetId, "路由执行失败", null, List.of(),
                Map.of("recommendedMode", "FAST"));
        var dataset = new EvaluationDataset(datasetId, organizationId, "路由增量续跑", "", Instant.EPOCH);
        var snapshot = Map.<String, Object>of(
                "execution", "ROUTING_ONLY", "mode", "AUTO", "knowledgeBaseIds", List.of(),
                "documentIds", List.of(), "filters", List.of(), "judgeMode", "NONE");
        var previous = new EvaluationRun(
                previousRunId, datasetId, EvaluationRunStatus.COMPLETED,
                Map.of("execution", "ROUTING_ONLY", "failedCases", 1),
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
        var next = new EvaluationRun(
                nextRunId, datasetId, EvaluationRunStatus.QUEUED, Map.of(), null, null, Instant.EPOCH);
        var repository = mock(EvaluationRepository.class);
        when(repository.findRun(organizationId, previousRunId)).thenReturn(Optional.of(previous));
        when(repository.findCases(organizationId, datasetId)).thenReturn(List.of(misclassifiedCase, failedCase));
        when(repository.findResults(organizationId, previousRunId)).thenReturn(List.of(
                new EvaluationResult(UUID.randomUUID(), previousRunId, misclassifiedCase.id(), null,
                        Map.of(
                                "execution", "ROUTING_ONLY", "expectedMode", "DEEP",
                                "selectedMode", "FAST", "routeDecisionSource", "LLM",
                                "routingCorrect", 0.0, "latencyMs", 12),
                        null, Instant.EPOCH),
                new EvaluationResult(UUID.randomUUID(), previousRunId, failedCase.id(), null,
                        Map.of(
                                "execution", "ROUTING_ONLY", "selectedMode", "ERROR",
                                "routeDecisionSource", "ERROR"),
                        "provider unavailable", Instant.EPOCH)));
        when(repository.createRun(eq(organizationId), eq(datasetId), any())).thenReturn(next);
        var attempts = mock(EvaluationAttemptPort.class);
        when(attempts.loadLineage(previousRunId)).thenReturn(Optional.of(
                new EvaluationRunLineage(previousRunId, previousRunId, null, 1, snapshot)));
        when(attempts.loadLineage(nextRunId)).thenReturn(Optional.of(
                new EvaluationRunLineage(nextRunId, previousRunId, previousRunId, 2, snapshot)));
        var coordinator = mock(RunCoordinator.class);
        when(coordinator.selectMode(eq(organizationId), any())).thenReturn(
                new RunCoordinator.Selection(RunMode.FAST, "direct-knowledge-query", false));
        var service = new EvaluationService(
                repository, emptyRetrieval(), Runnable::run, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                coordinator, mock(EvaluationJudge.class), attempts, 2);

        service.resumeRun(organizationId, userId, previousRunId, null);

        verify(coordinator, times(1)).selectMode(eq(organizationId), any());
        @SuppressWarnings("unchecked")
        var reused = ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(repository).saveResult(eq(nextRunId), eq(misclassifiedCase.id()), reused.capture(), isNull());
        assertEquals(0.0, reused.getValue().get("routingCorrect"));
        assertEquals(true, reused.getValue().get("reusedSuccessfulResult"));
        @SuppressWarnings("unchecked")
        var aggregate = ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(repository).completeRun(eq(nextRunId), aggregate.capture());
        assertEquals(1, aggregate.getValue().get("reusedSuccessfulCases"));
        assertEquals(1, aggregate.getValue().get("retriedCases"));
        assertEquals(0.5, aggregate.getValue().get("routingAccuracy"));
        assertEquals(0, aggregate.getValue().get("remainingFailedCases"));
    }

    @Test
    void cancellingAQueuedEvaluationPreventsAnyRagRunFromStarting() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var evaluationRunId = UUID.randomUUID();
        var evaluationCase = new EvaluationCase(
                UUID.randomUUID(), datasetId, "不应执行", null, List.of(), Map.of());
        var dataset = new EvaluationDataset(datasetId, organizationId, "取消", "", Instant.EPOCH);
        var queued = new EvaluationRun(
                evaluationRunId, datasetId, EvaluationRunStatus.QUEUED, Map.of(), null, null, Instant.EPOCH);
        var repository = mock(EvaluationRepository.class);
        when(repository.findDataset(organizationId, datasetId)).thenReturn(Optional.of(dataset));
        when(repository.findCases(organizationId, datasetId)).thenReturn(List.of(evaluationCase));
        when(repository.createRun(eq(organizationId), eq(datasetId), any())).thenReturn(queued);
        when(repository.findRun(organizationId, evaluationRunId)).thenReturn(Optional.of(queued));
        when(repository.cancelRun(organizationId, evaluationRunId)).thenReturn(true);
        var queuedTask = new AtomicReference<Runnable>();
        java.util.concurrent.Executor deferred = queuedTask::set;
        var coordinator = mock(RunCoordinator.class);
        var service = new EvaluationService(
                repository, emptyRetrieval(), deferred, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                coordinator, mock(EvaluationJudge.class), null, 2);

        service.startRagRun(organizationId, userId, datasetId,
                new StartEvaluationRunRequest(
                        RunMode.DEEP, KnowledgeScope.all(), List.of(), null, EvaluationJudgeMode.NONE));
        service.cancelRun(organizationId, evaluationRunId);
        queuedTask.get().run();

        verify(repository).cancelRun(organizationId, evaluationRunId);
        verify(coordinator, times(0)).start(any(), any(), any(), any());
        verify(repository, times(0)).completeRun(any(), any());
    }

    @Test
    void exportsAndTransactionallyImportsTheVersionedDatasetBundle() {
        var organizationId = UUID.randomUUID();
        var sourceDatasetId = UUID.randomUUID();
        var importedDatasetId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var evaluationCase = new EvaluationCase(
                UUID.randomUUID(), sourceDatasetId, "Which policy applies?", "The current policy",
                List.of(documentId), Map.of("domain", "support"));
        var sourceRepository = new InMemoryRepository(
                new EvaluationDataset(sourceDatasetId, organizationId, "Policy regression", "release checks",
                        Instant.EPOCH), evaluationCase);
        var clock = Clock.fixed(Instant.parse("2026-07-13T08:00:00Z"), ZoneOffset.UTC);
        var sourceService = new EvaluationService(sourceRepository, emptyRetrieval(), Runnable::run, clock);

        var bundle = sourceService.exportDataset(organizationId, sourceDatasetId);

        assertEquals(EvaluationDatasetBundle.SCHEMA_VERSION, bundle.schemaVersion());
        assertEquals(sourceDatasetId, bundle.sourceDatasetId());
        assertEquals(1, bundle.cases().size());

        var targetRepository = mock(EvaluationRepository.class);
        var importedDataset = new EvaluationDataset(
                importedDatasetId, organizationId, bundle.name(), bundle.description(), clock.instant());
        var importedCase = new EvaluationCase(
                UUID.randomUUID(), importedDatasetId, evaluationCase.question(), evaluationCase.expectedAnswer(),
                evaluationCase.expectedDocumentIds(), evaluationCase.metadata());
        when(targetRepository.findOwnedDocumentIds(organizationId, List.of(documentId)))
                .thenReturn(Set.of(documentId));
        when(targetRepository.createDataset(organizationId, bundle.name(), bundle.description()))
                .thenReturn(importedDataset);
        when(targetRepository.addCase(
                organizationId, importedDatasetId, evaluationCase.question(), evaluationCase.expectedAnswer(),
                List.of(documentId), evaluationCase.metadata())).thenReturn(importedCase);
        when(targetRepository.findDataset(organizationId, importedDatasetId)).thenReturn(Optional.of(importedDataset));
        when(targetRepository.findCases(organizationId, importedDatasetId)).thenReturn(List.of(importedCase));
        when(targetRepository.findRuns(organizationId, importedDatasetId)).thenReturn(List.of());
        var targetService = new EvaluationService(targetRepository, emptyRetrieval(), Runnable::run, clock);

        var imported = targetService.importDataset(organizationId, bundle);

        assertEquals(importedDatasetId, imported.dataset().id());
        assertEquals(1, imported.cases().size());
        verify(targetRepository).addCase(
                organizationId, importedDatasetId, evaluationCase.question(), evaluationCase.expectedAnswer(),
                List.of(documentId), evaluationCase.metadata());
    }

    @Test
    void computesRetrievalMetricsAndNormalizesAnswerPunctuation() {
        var organizationId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var forbiddenDocumentId = UUID.randomUUID();
        var repository = new InMemoryRepository(
                new EvaluationDataset(datasetId, organizationId, "markers", "", Instant.EPOCH),
                new EvaluationCase(UUID.randomUUID(), datasetId, "What is the marker?",
                        "PINEAPPLE-QUASAR-42", List.of(documentId),
                        Map.of("forbiddenDocumentIds", List.of(forbiddenDocumentId.toString())))
        );
        var hit = new RetrievalHit(
                UUID.randomUUID(), null, documentId, UUID.randomUUID(), "Marker document",
                "Distinctive marker line: PINEAPPLE-QUASAR-42.", 0.9, List.of("test"));
        var forbiddenHit = new RetrievalHit(
                UUID.randomUUID(), null, forbiddenDocumentId, UUID.randomUUID(), "Expired marker document",
                "This stale document must not be recalled.", 0.8, List.of("test"));
        RetrievalPort retrieval = new RetrievalPort() {
            @Override
            public List<RetrievalHit> keywordSearch(String query, RetrievalScope scope, int topK) {
                return List.of(hit, forbiddenHit);
            }

            @Override
            public List<RetrievalHit> semanticSearch(String query, RetrievalScope scope, int topK, int overFetch) {
                return List.of(hit, forbiddenHit);
            }

            @Override
            public List<RetrievalHit> expandContext(List<RetrievalHit> hits, int finalGroups) {
                return hits;
            }
        };
        var service = new EvaluationService(
                repository,
                retrieval,
                Runnable::run,
                Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC)
        );

        var started = service.startRun(organizationId, datasetId);
        var detail = service.run(organizationId, started.id());

        assertEquals("COMPLETED", detail.run().status());
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "recallAt5"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "recallAt10"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "mrrAt5"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "mrr"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "hitAt5"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "hitAt10"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "expectedAnswerCoverage"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "forbiddenDocumentHitCount"));
        assertEquals(0.0, number(detail.run().aggregateMetrics(), "forbiddenDocumentLeakFreeRate"));
        assertEquals(1, detail.results().size());
    }

    @Test
    void aggregatesRoutingAccuracyConfusionSourcesAndMeanLatency() {
        var organizationId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var cases = List.of(
                new EvaluationCase(UUID.randomUUID(), datasetId, "direct", null, List.of(),
                        Map.of("recommendedMode", "FAST")),
                new EvaluationCase(UUID.randomUUID(), datasetId, "complex", null, List.of(),
                        Map.of("recommendedMode", "DEEP")),
                new EvaluationCase(UUID.randomUUID(), datasetId, "misclassified", null, List.of(),
                        Map.of("recommendedMode", "DEEP")),
                new EvaluationCase(UUID.randomUUID(), datasetId, "fallback", null, List.of(),
                        Map.of("recommendedMode", "DEEP")));
        var dataset = new EvaluationDataset(datasetId, organizationId, "routing", "", Instant.EPOCH);
        var repository = mock(EvaluationRepository.class);
        var evaluationRun = new EvaluationRun(
                UUID.randomUUID(), datasetId, EvaluationRunStatus.QUEUED, Map.of(), null, null, Instant.EPOCH);
        when(repository.findDataset(organizationId, datasetId)).thenReturn(Optional.of(dataset));
        when(repository.findCases(organizationId, datasetId)).thenReturn(cases);
        when(repository.createRun(eq(organizationId), eq(datasetId), any())).thenReturn(evaluationRun);
        var coordinator = mock(RunCoordinator.class);
        when(coordinator.selectMode(eq(organizationId), any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(1, com.yanyue.rag.contract.chat.CreateRunRequest.class);
            return switch (request.query()) {
                case "direct" -> new RunCoordinator.Selection(RunMode.FAST, "direct-knowledge-query", false);
                case "complex" -> new RunCoordinator.Selection(RunMode.DEEP, "structured-classifier", true);
                case "misclassified" -> new RunCoordinator.Selection(RunMode.FAST, "structured-classifier", true);
                default -> new RunCoordinator.Selection(RunMode.DEEP, "router-fallback-deep", false);
            };
        });
        var service = new EvaluationService(
                repository, emptyRetrieval(), Runnable::run,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), coordinator, mock(EvaluationJudge.class), null, 2);

        service.startRoutingRun(organizationId, datasetId,
                new StartEvaluationRunRequest(
                        RunMode.AUTO, KnowledgeScope.all(), List.of(), null, EvaluationJudgeMode.NONE));

        @SuppressWarnings("unchecked")
        var aggregate = org.mockito.ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(repository).completeRun(eq(evaluationRun.id()), aggregate.capture());
        var values = aggregate.getValue();
        assertEquals("ROUTING_ONLY", values.get("execution"));
        assertEquals(4, values.get("caseCount"));
        assertEquals(0.75, number(values, "routingAccuracy"));
        assertEquals(1.0, number(values, "fastRouteRecall"));
        assertEquals(2.0 / 3.0, number(values, "deepRouteRecall"));
        assertEquals(0.5, number(values, "fastRoutePrecision"));
        assertEquals(1.0, number(values, "deepRoutePrecision"));
        assertTrue(number(values, "averageLatencyMs") >= 0);
        assertEquals(3L, values.get("classifierAttemptCount"));
        assertEquals(2.0 / 3.0, number(values, "classifierSuccessRate"));
        assertEquals(1L, values.get("routerFallbackCount"));
        assertEquals(0.5, number(values, "llmRoutingAccuracy"));
        assertEquals(1.0, number(values, "heuristicRoutingAccuracy"));
        assertEquals(1.0, number(values, "fallbackRoutingAccuracy"));
        @SuppressWarnings("unchecked")
        var confusion = (Map<String, Object>) values.get("routingConfusionMatrix");
        assertEquals(1L, ((Map<?, ?>) confusion.get("FAST")).get("FAST"));
        assertEquals(1L, ((Map<?, ?>) confusion.get("DEEP")).get("FAST"));
        assertEquals(2L, ((Map<?, ?>) confusion.get("DEEP")).get("DEEP"));
    }

    @Test
    void gradesACompletedRagRunFromPersistedAnswerAndCitations() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var ragRunId = UUID.randomUUID();
        var repository = new InMemoryRepository(
                new EvaluationDataset(datasetId, organizationId, "rag", "", Instant.EPOCH),
                new EvaluationCase(UUID.randomUUID(), datasetId, "What is published?",
                        "current policy applies", List.of(documentId), Map.of("expectNoAnswer", false))
        );
        repository.ragRunId = ragRunId;
        repository.ragCandidates = List.of(new RetrievalHit(
                UUID.randomUUID(), null, documentId, UUID.randomUUID(), "Policy",
                "Retrieved background only.", 0.9, List.of("semantic")));
        repository.ragAcceptedEvidenceTexts = List.of("Evidence background only.");
        repository.ragOutcome = new EvaluationRepository.RagRunOutcome(
                ragRunId, "COMPLETED", "FAST", "The current policy applies.", null,
                Map.of("pipelineVersion", "fast-v1"), 2, 2, 0,
                Instant.EPOCH, Instant.EPOCH.plusMillis(120), null);
        var coordinator = mock(RunCoordinator.class);
        var judge = mock(EvaluationJudge.class);
        when(coordinator.start(any(), any(), any(), any())).thenReturn(
                new RunAcceptedResponse(ragRunId, RunMode.FAST, "/events"));
        when(judge.judge(any(), any(), any(), any(), any(), any())).thenReturn(Map.of(
                "judgeStatus", "COMPLETED",
                "semanticAnswerVerdict", "CORRECT",
                "semanticAnswerScore", 0.9
        ));
        var service = new EvaluationService(repository, emptyRetrieval(), Runnable::run,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), coordinator, judge);

        var started = service.startRagRun(organizationId, userId, datasetId,
                new StartEvaluationRunRequest(
                        RunMode.FAST, KnowledgeScope.all(), List.of(), null, EvaluationJudgeMode.ANSWER));
        var detail = service.run(organizationId, started.id());

        assertEquals("COMPLETED", detail.run().status());
        assertEquals("RAG", detail.run().aggregateMetrics().get("execution"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "citationResolvableRate"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "noAnswerAccuracy"));
        assertEquals(0.9, number(detail.run().aggregateMetrics(), "semanticAnswerScore"));
        assertEquals(0.0, number(detail.run().aggregateMetrics(), "citationEntailmentScore"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "expectedAnswerCoverage"));
        assertTrue(number(detail.run().aggregateMetrics(), "researchContextCoverage") < 1.0);
        assertEquals(ragRunId, detail.results().getFirst().ragRunId());
        assertEquals("The current policy applies.", detail.results().getFirst().metrics().get("answer"));
    }

    @Test
    void runsAgenticRetrievalThroughDeepEvidenceWithoutAnswerGeneration() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var ragRunId = UUID.randomUUID();
        var repository = new InMemoryRepository(
                new EvaluationDataset(datasetId, organizationId, "agentic retrieval", "", Instant.EPOCH),
                new EvaluationCase(UUID.randomUUID(), datasetId, "Compare both deployment requirements.",
                        "primary evidence supplement", List.of(documentId), Map.of())
        );
        repository.ragRunId = ragRunId;
        repository.ragCandidates = List.of(new RetrievalHit(
                UUID.randomUUID(), null, documentId, UUID.randomUUID(), "Deployment guide",
                "primary evidence", 0.95, List.of("agent-deep-read")));
        repository.ragAcceptedEvidenceTexts = List.of("supplement");
        repository.ragOutcome = new EvaluationRepository.RagRunOutcome(
                ragRunId, "COMPLETED", "DEEP", "", null,
                Map.of("pipelineVersion", "agentic-v1", "answerGenerationSkipped", true),
                0, 0, 0, Instant.EPOCH, Instant.EPOCH.plusMillis(240), null);
        var coordinator = mock(RunCoordinator.class);
        var judge = mock(EvaluationJudge.class);
        when(coordinator.startAgenticRetrieval(any(), any(), any(), any())).thenReturn(
                new RunAcceptedResponse(ragRunId, RunMode.DEEP, "/events"));
        var service = new EvaluationService(repository, emptyRetrieval(), Runnable::run,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), coordinator, judge);

        var started = service.startAgenticRetrievalRun(
                organizationId, userId, datasetId, StartEvaluationRunRequest.defaults());
        var detail = service.run(organizationId, started.id());

        assertEquals("COMPLETED", detail.run().status());
        assertEquals("AGENTIC_RETRIEVAL_ONLY", detail.run().aggregateMetrics().get("execution"));
        assertEquals(true, detail.run().aggregateMetrics().get("answerGenerationSkipped"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "recallAt5"));
        assertTrue(number(detail.run().aggregateMetrics(), "retrievalCandidateCoverage") < 1.0);
        assertTrue(number(detail.run().aggregateMetrics(), "acceptedEvidenceCoverage") < 1.0);
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "researchContextCoverage"));
        assertEquals(1.0, number(detail.run().aggregateMetrics(), "expectedAnswerCoverage"));
        assertEquals(0, detail.run().aggregateMetrics().get("ragExecutedCases"));
        assertEquals(ragRunId, detail.results().getFirst().ragRunId());
        assertEquals("AGENTIC_RETRIEVAL_ONLY", detail.results().getFirst().metrics().get("execution"));
        assertEquals(true, detail.results().getFirst().metrics().get("answerGenerationSkipped"));
        verify(coordinator).startAgenticRetrieval(eq(organizationId), eq(userId), any(), any());
        verify(coordinator, times(0)).start(any(), any(), any(), any());
        verify(judge, times(0)).judge(any(), any(), any(), any(), any(), any());
    }

    @Test
    void persistsStructuredFailureMetricsForACompletedEvaluationCaseAttempt() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var ragRunId = UUID.randomUUID();
        var repository = new InMemoryRepository(
                new EvaluationDataset(datasetId, organizationId, "failed rag", "", Instant.EPOCH),
                new EvaluationCase(UUID.randomUUID(), datasetId, "Find the unavailable policy", null,
                        List.of(), Map.of("recommendedMode", "DEEP")));
        repository.ragOutcome = new EvaluationRepository.RagRunOutcome(
                ragRunId, "FAILED", "DEEP", "", null, Map.of("pipelineVersion", "agentic-hybrid-v2"),
                0, 0, 0, Instant.EPOCH, Instant.EPOCH.plusMillis(75),
                "retrieval backend unavailable");
        var coordinator = mock(RunCoordinator.class);
        when(coordinator.start(any(), any(), any(), any())).thenReturn(
                new RunAcceptedResponse(ragRunId, RunMode.DEEP, "/events"));
        var service = new EvaluationService(
                repository, emptyRetrieval(), Runnable::run, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                coordinator, mock(EvaluationJudge.class));

        var started = service.startRagRun(
                organizationId, userId, datasetId,
                new StartEvaluationRunRequest(
                        RunMode.DEEP, KnowledgeScope.all(), List.of(), null, EvaluationJudgeMode.NONE));
        var detail = service.run(organizationId, started.id());

        assertEquals("COMPLETED", detail.run().status());
        assertEquals(1, detail.run().aggregateMetrics().get("failedCases"));
        assertEquals("retrieval backend unavailable", detail.results().getFirst().errorMessage());
        assertEquals("RETRIEVAL", detail.results().getFirst().metrics().get("failurePhase"));
        assertEquals("IllegalStateException", detail.results().getFirst().metrics().get("failureType"));
        assertEquals(ragRunId, detail.results().getFirst().metrics().get("ragRunId"));
        assertEquals("DEEP", detail.results().getFirst().metrics().get("selectedMode"));
        assertTrue(detail.results().getFirst().metrics().containsKey("latencyMs"));
    }

    @Test
    void groupedCasesReuseOneConversationAndPreserveTurnOrder() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var evaluationRunId = UUID.randomUUID();
        var groupedConversationId = UUID.randomUUID();
        var standaloneConversationId = UUID.randomUUID();
        var ragRunIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var cases = List.of(
                new EvaluationCase(UUID.randomUUID(), datasetId, "专业版年度目录价是多少？", "88000元",
                        List.of(), Map.of("conversationGroup", "product-follow-up", "conversationTurn", 1), 1),
                new EvaluationCase(UUID.randomUUID(), datasetId, "它加上黄金支持包呢？", "114000元",
                        List.of(), Map.of("conversationGroup", "product-follow-up", "conversationTurn", 2), 2),
                new EvaluationCase(UUID.randomUUID(), datasetId, "A 级数据的 RPO 是多少？", "5分钟",
                        List.of(), Map.of(), 3)
        );
        var repository = mock(EvaluationRepository.class);
        var dataset = new EvaluationDataset(datasetId, organizationId, "conversation benchmark", "", Instant.EPOCH);
        var evaluationRun = new EvaluationRun(
                evaluationRunId, datasetId, EvaluationRunStatus.QUEUED, Map.of(), null, null, Instant.EPOCH);
        when(repository.findDataset(organizationId, datasetId)).thenReturn(Optional.of(dataset));
        when(repository.findCases(organizationId, datasetId)).thenReturn(cases);
        when(repository.createRun(eq(organizationId), eq(datasetId), any())).thenReturn(evaluationRun);
        when(repository.createEvaluationConversation(organizationId, userId, evaluationRunId))
                .thenReturn(groupedConversationId, standaloneConversationId);
        when(repository.findRagRunCandidates(eq(organizationId), any())).thenReturn(List.of());
        when(repository.findRagRunOutcome(eq(organizationId), any())).thenAnswer(invocation -> {
            var ragRunId = invocation.getArgument(1, UUID.class);
            return Optional.of(new EvaluationRepository.RagRunOutcome(
                    ragRunId, "COMPLETED", "FAST", "answer", null, Map.of(),
                    0, 0, 0, Instant.EPOCH, Instant.EPOCH.plusMillis(10), null));
        });
        var coordinator = mock(RunCoordinator.class);
        var nextRun = new AtomicInteger();
        when(coordinator.start(eq(organizationId), eq(userId), any(), any())).thenAnswer(invocation -> {
            var ragRunId = ragRunIds.get(nextRun.getAndIncrement());
            return new RunAcceptedResponse(ragRunId, RunMode.FAST, "/events");
        });
        var service = new EvaluationService(
                repository, emptyRetrieval(), Runnable::run, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                coordinator, mock(EvaluationJudge.class));

        service.startRagRun(organizationId, userId, datasetId,
                new StartEvaluationRunRequest(
                        RunMode.FAST, KnowledgeScope.all(), List.of(), null, EvaluationJudgeMode.NONE));

        var conversationIds = ArgumentCaptor.forClass(UUID.class);
        verify(coordinator, times(3)).start(
                eq(organizationId), eq(userId), conversationIds.capture(), any());
        assertEquals(
                List.of(groupedConversationId, groupedConversationId, standaloneConversationId),
                conversationIds.getAllValues());
        verify(repository, times(2)).createEvaluationConversation(
                organizationId, userId, evaluationRunId);

        @SuppressWarnings("unchecked")
        var metrics = ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(repository, times(3)).saveResult(
                eq(evaluationRunId), any(), any(UUID.class), metrics.capture(), isNull());
        assertEquals(false, metrics.getAllValues().get(0).get("conversationReused"));
        assertEquals(true, metrics.getAllValues().get(1).get("conversationReused"));
        assertEquals(2, metrics.getAllValues().get(1).get("conversationTurn"));

        @SuppressWarnings("unchecked")
        var aggregate = ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(repository).completeRun(eq(evaluationRunId), aggregate.capture());
        assertEquals(1, aggregate.getValue().get("conversationGroupCount"));
        assertEquals(2, aggregate.getValue().get("groupedCaseCount"));
        assertEquals(2, aggregate.getValue().get("evaluationConversationCount"));
    }

    @Test
    void evidenceLimitedAnswerCountsAsAnExplicitAbstention() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var ragRunId = UUID.randomUUID();
        var repository = new InMemoryRepository(
                new EvaluationDataset(datasetId, organizationId, "no answer", "", Instant.EPOCH),
                new EvaluationCase(
                        UUID.randomUUID(), datasetId, "Will the price rise next year?",
                        "The knowledge base does not provide next year's price.", List.of(),
                        Map.of("expectNoAnswer", true))
        );
        repository.ragRunId = ragRunId;
        repository.ragOutcome = new EvaluationRepository.RagRunOutcome(
                ragRunId, "COMPLETED", "FAST",
                "无法确定。现有资料仅说明今年价格，未提供下一年度价格或涨价安排。", null,
                Map.of(), 0, 0, 0, Instant.EPOCH, Instant.EPOCH.plusMillis(50), null);
        var coordinator = mock(RunCoordinator.class);
        when(coordinator.start(any(), any(), any(), any())).thenReturn(
                new RunAcceptedResponse(ragRunId, RunMode.FAST, "/events"));
        var service = new EvaluationService(
                repository, emptyRetrieval(), Runnable::run, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                coordinator, mock(EvaluationJudge.class));

        var started = service.startRagRun(
                organizationId, userId, datasetId, StartEvaluationRunRequest.defaults());
        var detail = service.run(organizationId, started.id());

        assertEquals(1.0, number(detail.run().aggregateMetrics(), "noAnswerAccuracy"));
        assertEquals(true, detail.results().getFirst().metrics().get("noAnswer"));
        assertEquals("EVIDENCE_LIMITED_ANSWER",
                detail.results().getFirst().metrics().get("noAnswerSource"));
    }

    @Test
    void rejectsConversationGroupsWithMissingTurns() {
        var organizationId = UUID.randomUUID();
        var bundle = new EvaluationDatasetBundle(
                EvaluationDatasetBundle.SCHEMA_VERSION, null, null, "invalid turns", "",
                List.of(
                        new EvaluationDatasetBundle.CaseEntry(
                                "first", "one", List.of(),
                                Map.of("conversationGroup", "follow-up", "conversationTurn", 1)),
                        new EvaluationDatasetBundle.CaseEntry(
                                "third", "three", List.of(),
                                Map.of("conversationGroup", "follow-up", "conversationTurn", 3))
                ));
        var service = new EvaluationService(
                mock(EvaluationRepository.class), emptyRetrieval(), Runnable::run,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        var failure = assertThrows(
                IllegalArgumentException.class, () -> service.importDataset(organizationId, bundle));

        assertEquals("Conversation group follow-up expects turn 2 but found 3", failure.getMessage());
    }

    private RetrievalPort emptyRetrieval() {
        return new RetrievalPort() {
            @Override
            public List<RetrievalHit> keywordSearch(String query, RetrievalScope scope, int topK) {
                return List.of();
            }

            @Override
            public List<RetrievalHit> semanticSearch(String query, RetrievalScope scope, int topK, int overFetch) {
                return List.of();
            }

            @Override
            public List<RetrievalHit> expandContext(List<RetrievalHit> hits, int finalGroups) {
                return hits;
            }
        };
    }

    private double number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).doubleValue();
    }

    private static final class InMemoryRepository implements EvaluationRepository {
        private final EvaluationDataset dataset;
        private final EvaluationCase evaluationCase;
        private final List<EvaluationResult> results = new ArrayList<>();
        private EvaluationRun run;
        private UUID ragRunId;
        private EvaluationRepository.RagRunOutcome ragOutcome;
        private List<RetrievalHit> ragCandidates = List.of();
        private List<String> ragAcceptedEvidenceTexts = List.of();

        private InMemoryRepository(EvaluationDataset dataset, EvaluationCase evaluationCase) {
            this.dataset = dataset;
            this.evaluationCase = evaluationCase;
        }

        @Override
        public EvaluationDataset createDataset(UUID organizationId, String name, String description) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EvaluationDataset> findDatasets(UUID organizationId) {
            return List.of(dataset);
        }

        @Override
        public Optional<EvaluationDataset> findDataset(UUID organizationId, UUID datasetId) {
            return dataset.id().equals(datasetId) && dataset.organizationId().equals(organizationId)
                    ? Optional.of(dataset) : Optional.empty();
        }

        @Override
        public EvaluationCase addCase(UUID organizationId, UUID datasetId, String question, String expectedAnswer,
                                      List<UUID> expectedDocumentIds, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EvaluationCase> findCases(UUID organizationId, UUID datasetId) {
            return List.of(evaluationCase);
        }

        @Override
        public boolean deleteCase(UUID organizationId, UUID datasetId, UUID caseId) {
            return false;
        }

        @Override
        public EvaluationRun createRun(UUID organizationId, UUID datasetId) {
            run = new EvaluationRun(UUID.randomUUID(), datasetId, EvaluationRunStatus.QUEUED,
                    Map.of(), null, null, Instant.EPOCH);
            return run;
        }

        @Override
        public void markRunRunning(UUID runId) {
            run = new EvaluationRun(run.id(), run.datasetId(), EvaluationRunStatus.RUNNING,
                    Map.of(), Instant.EPOCH, null, run.createdAt());
        }

        @Override
        public void completeRun(UUID runId, Map<String, Object> aggregateMetrics) {
            run = new EvaluationRun(run.id(), run.datasetId(), EvaluationRunStatus.COMPLETED,
                    aggregateMetrics, run.startedAt(), Instant.EPOCH, run.createdAt());
        }

        @Override
        public void failRun(UUID runId, String message) {
            run = new EvaluationRun(run.id(), run.datasetId(), EvaluationRunStatus.FAILED,
                    Map.of("error", message), run.startedAt(), Instant.EPOCH, run.createdAt());
        }

        @Override
        public List<EvaluationRun> findRuns(UUID organizationId, UUID datasetId) {
            return run == null ? List.of() : List.of(run);
        }

        @Override
        public Optional<EvaluationRun> findRun(UUID organizationId, UUID runId) {
            return run != null && run.id().equals(runId) ? Optional.of(run) : Optional.empty();
        }

        @Override
        public void saveResult(UUID runId, UUID caseId, Map<String, Object> metrics, String errorMessage) {
            results.add(new EvaluationResult(
                    UUID.randomUUID(), runId, caseId, null, metrics, errorMessage, Instant.EPOCH));
        }

        @Override
        public void saveResult(UUID runId, UUID caseId, UUID resultRagRunId,
                               Map<String, Object> metrics, String errorMessage) {
            results.add(new EvaluationResult(
                    UUID.randomUUID(), runId, caseId, resultRagRunId, metrics, errorMessage, Instant.EPOCH));
        }

        @Override
        public UUID createEvaluationConversation(UUID organizationId, UUID userId, UUID evaluationRunId) {
            return UUID.randomUUID();
        }

        @Override
        public Optional<RagRunOutcome> findRagRunOutcome(UUID organizationId, UUID resultRagRunId) {
            return Optional.ofNullable(ragOutcome);
        }

        @Override
        public List<RetrievalHit> findRagRunCandidates(UUID organizationId, UUID resultRagRunId) {
            return ragCandidates;
        }

        @Override
        public List<String> findRagRunAcceptedEvidenceTexts(UUID organizationId, UUID resultRagRunId) {
            return ragAcceptedEvidenceTexts;
        }

        @Override
        public List<EvaluationResult> findResults(UUID organizationId, UUID runId) {
            return List.copyOf(results);
        }
    }
}
