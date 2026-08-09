package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.application.chat.deep.DeepRagPipeline;
import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.port.RunRecordPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RunCoordinatorTest {
    @Test
    void startsEndToEndClockBeforeAutoDeepPipelineExecution() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var now = Instant.parse("2026-07-21T00:00:00Z");
        var query = "请综合分析多个制度之间的适用条件、差异、潜在冲突，并分别给出每一项判断所依据的条款。";
        var runRecords = mock(RunRecordPort.class);
        var deep = mock(DeepRagPipeline.class);
        var events = mock(RunEventHub.class);
        var fast = mock(FastRagPipeline.class);
        when(deep.execute(any(), eq(conversationId), eq(organizationId), eq(userId), any(), eq(true)))
                .thenReturn("answer");
        var request = new CreateRunRequest(query, RunMode.AUTO, KnowledgeScope.all(), List.of(), null);
        when(fast.prepareRouting(organizationId, userId, request)).thenReturn(new FastRagPipeline.RoutingPreflight(
                query, List.of(), List.of(), List.of(), now, 0));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var coordinator = new RunCoordinator(
                    fast, deep, events, executor, runRecords, RagTelemetry.noop(), new AutoModeRouter());

            var accepted = coordinator.start(organizationId, userId, conversationId, request);

            verify(runRecords, timeout(2_000)).complete(accepted.runId());
            var ordered = inOrder(runRecords, deep);
            ordered.verify(runRecords).create(
                    accepted.runId(), organizationId, userId, conversationId, request);
            ordered.verify(runRecords).markRouting(accepted.runId());
            ordered.verify(runRecords).markRunning(accepted.runId(), RunMode.DEEP);
            ordered.verify(deep).execute(
                    accepted.runId(), conversationId, organizationId, userId, request, true);
            ordered.verify(runRecords).complete(accepted.runId());
            verify(events, timeout(2_000)).publish(accepted.runId(), StreamEventType.ROUTE_SELECTED,
                    java.util.Map.of(
                            "requested", RunMode.AUTO,
                            "selected", RunMode.DEEP,
                            "reason", "auto-deep-retrieval-confidence",
                            "routeDecisionSource", "HEURISTIC",
                            "signals", List.of("MULTI_GOAL", "SYNTHESIS", "COMPARISON", "CONFLICT"),
                            "routerProfile", "RETRIEVAL_AWARE_50",
                            "titleHitCount", 0,
                            "preflightCandidateCount", 0,
                            "preflightLatencyMs", 0L,
                            "preflightTop5", List.of()));
        }
    }

    @Test
    void autoUsesLocalRouterWithoutCallingStructuredRouter() {
        var organizationId = UUID.randomUUID();
        var now = Instant.parse("2026-07-21T00:00:00Z");
        var query = "请综合分析多个制度之间的适用条件、差异、潜在冲突，并分别给出每一项判断所依据的条款。";
        var fast = mock(FastRagPipeline.class);
        var request = new CreateRunRequest(query, RunMode.AUTO, KnowledgeScope.all(), List.of(), null);
        when(fast.prepareRouting(eq(organizationId), eq(null), eq(request))).thenReturn(
                new FastRagPipeline.RoutingPreflight(query, List.of(), List.of(), List.of(), now, 0));
        var coordinator = new RunCoordinator(
                fast, mock(DeepRagPipeline.class), mock(RunEventHub.class),
                mock(ExecutorService.class), mock(RunRecordPort.class), RagTelemetry.noop(), new AutoModeRouter());

        var selection = coordinator.selectMode(organizationId, request);

        assertEquals(RunMode.DEEP, selection.mode());
        assertEquals("auto-deep-retrieval-confidence", selection.reason());
        assertFalse(selection.classifiedByModel());
        assertEquals("HEURISTIC", selection.decisionSource());
        assertEquals(List.of("MULTI_GOAL", "SYNTHESIS", "COMPARISON", "CONFLICT"), selection.signals());
        assertEquals(0, selection.titleHitCount());
    }

    @Test
    void autoCanSelectFastThroughLocalRouter() {
        var coordinator = new RunCoordinator(
                mock(FastRagPipeline.class), mock(DeepRagPipeline.class), mock(RunEventHub.class),
                mock(ExecutorService.class), mock(RunRecordPort.class), RagTelemetry.noop(), new AutoModeRouter());
        var request = new CreateRunRequest(
                "ResourceClaimSpec 的作用是什么？", RunMode.AUTO, KnowledgeScope.all(), List.of(), null);

        var selection = coordinator.selectMode(UUID.randomUUID(), request);

        assertEquals(RunMode.FAST, selection.mode());
        assertEquals("auto-fast-technical-anchor", selection.reason());
        assertEquals("HEURISTIC", selection.decisionSource());
        assertEquals(List.of("STABLE_TECHNICAL_ANCHOR"), selection.signals());
    }

    @Test
    void explicitModesRemainUserOverridesWithoutCallingRouter() {
        var router = mock(AutoModeRouter.class);
        var coordinator = new RunCoordinator(
                mock(FastRagPipeline.class), mock(DeepRagPipeline.class), mock(RunEventHub.class),
                mock(ExecutorService.class), mock(RunRecordPort.class), RagTelemetry.noop(), router);

        for (var mode : List.of(RunMode.FAST, RunMode.DEEP)) {
            var request = new CreateRunRequest("任意问题", mode, KnowledgeScope.all(), List.of(), null);
            var selection = coordinator.selectMode(UUID.randomUUID(), request);

            assertEquals(mode, selection.mode());
            assertEquals("user-override", selection.reason());
            assertEquals("USER_OVERRIDE", selection.decisionSource());
            assertEquals(List.of(), selection.signals());
        }
        verify(router, never()).route(any());
    }

    @Test
    void retrievalAwareAutoRouteReusesPreflightWhenSelectingFast() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var query = "同时处理 Kubernetes Pod 与 Webhook 两个独立目标，并分别回答。";
        var request = new CreateRunRequest(query, RunMode.AUTO, KnowledgeScope.all(), List.of(), null);
        var fast = mock(FastRagPipeline.class);
        var candidate = new RetrievalHit(UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                "Kubernetes Pod", "context", 1.0, List.of("rrf"));
        var preflight = new FastRagPipeline.RoutingPreflight(
                query, List.of(candidate), List.of(), List.of(candidate), Instant.parse("2026-07-21T00:00:00Z"), 12);
        when(fast.prepareRouting(organizationId, userId, request)).thenReturn(preflight);
        when(fast.execute(any(), eq(conversationId), eq(organizationId), eq(userId), eq(request), eq(preflight)))
                .thenReturn("answer");
        var runRecords = mock(RunRecordPort.class);
        var deep = mock(DeepRagPipeline.class);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var coordinator = new RunCoordinator(fast, deep, mock(RunEventHub.class), executor,
                    runRecords, RagTelemetry.noop(), new AutoModeRouter());

            var accepted = coordinator.start(organizationId, userId, conversationId, request);

            verify(runRecords, timeout(2_000)).complete(accepted.runId());
            verify(fast).prepareRouting(organizationId, userId, request);
            verify(fast).execute(accepted.runId(), conversationId, organizationId, userId, request, preflight);
            verify(deep, never()).execute(any(), any(), any(), any(), any(), eq(true));
        }
    }

    @Test
    void retrievalPreflightFailureFallsBackToDeep() {
        var organizationId = UUID.randomUUID();
        var query = "同时处理 Kubernetes Pod 与 Webhook 两个独立目标，并分别回答。";
        var request = new CreateRunRequest(query, RunMode.AUTO, KnowledgeScope.all(), List.of(), null);
        var fast = mock(FastRagPipeline.class);
        when(fast.prepareRouting(organizationId, null, request))
                .thenThrow(new IllegalStateException("retrieval unavailable"));
        var coordinator = new RunCoordinator(
                fast, mock(DeepRagPipeline.class), mock(RunEventHub.class), mock(ExecutorService.class),
                mock(RunRecordPort.class), RagTelemetry.noop(), new AutoModeRouter());

        var selection = coordinator.selectMode(organizationId, request);

        assertEquals(RunMode.DEEP, selection.mode());
        assertEquals("auto-deep-retrieval-preflight-error", selection.reason());
        assertEquals("FALLBACK", selection.decisionSource());
        assertEquals("RETRIEVAL_AWARE_50", selection.routerProfile());
        assertEquals(-1, selection.titleHitCount());
    }

    @Test
    void deepDeadlineFailurePersistsExplicitStopReason() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var runRecords = mock(RunRecordPort.class);
        var deep = mock(DeepRagPipeline.class);
        when(deep.execute(any(), eq(conversationId), eq(organizationId), eq(userId), any(), eq(true)))
                .thenThrow(new IllegalStateException("Run Deadline 已耗尽"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var coordinator = new RunCoordinator(mock(FastRagPipeline.class), deep, mock(RunEventHub.class),
                    executor, runRecords, RagTelemetry.noop(), new AutoModeRouter());
            var request = new CreateRunRequest("复杂问题", RunMode.DEEP, KnowledgeScope.all(), List.of(), null);

            var accepted = coordinator.start(organizationId, userId, conversationId, request);

            verify(runRecords, timeout(2_000)).fail(
                    accepted.runId(), "Run Deadline 已耗尽", "DEADLINE_EXCEEDED");
            verify(runRecords, never()).fail(accepted.runId(), "Run Deadline 已耗尽");
        }
    }
}
