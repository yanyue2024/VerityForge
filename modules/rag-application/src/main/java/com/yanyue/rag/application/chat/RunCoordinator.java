package com.yanyue.rag.application.chat;

import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.RunAcceptedResponse;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.application.chat.deep.DeepRagPipeline;
import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.domain.port.RunRecordPort;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class RunCoordinator {
    private final FastRagPipeline fastPipeline;
    private final DeepRagPipeline deepPipeline;
    private final RunEventHub events;
    private final ExecutorService executor;
    private final RunRecordPort runRecords;
    private final AutoModeRouter autoModeRouter;
    private final RagTelemetry telemetry;
    private final ConcurrentHashMap<UUID, Future<?>> activeRuns = new ConcurrentHashMap<>();

    public RunCoordinator(FastRagPipeline fastPipeline, DeepRagPipeline deepPipeline, RunEventHub events,
                          @Qualifier("ragRunExecutor") ExecutorService executor, RunRecordPort runRecords,
                          RagTelemetry telemetry, AutoModeRouter autoModeRouter) {
        this.fastPipeline = fastPipeline;
        this.deepPipeline = deepPipeline;
        this.events = events;
        this.executor = executor;
        this.runRecords = runRecords;
        this.autoModeRouter = autoModeRouter;
        this.telemetry = telemetry;
    }

    public RunAcceptedResponse start(UUID organizationId, UUID userId, UUID conversationId, CreateRunRequest request) {
        var runId = UUID.randomUUID();
        runRecords.create(runId, organizationId, userId, conversationId, request);
        return schedule(runId, organizationId, userId, conversationId, request, false);
    }

    public RunAcceptedResponse reprocess(UUID organizationId, UUID userId, UUID sourceRunId) {
        var runId = UUID.randomUUID();
        var seed = runRecords.prepareReprocess(sourceRunId, runId, organizationId, userId);
        return schedule(runId, organizationId, userId, seed.conversationId(), seed.request(), true);
    }

    private RunAcceptedResponse schedule(
            UUID runId,
            UUID organizationId,
            UUID userId,
            UUID conversationId,
            CreateRunRequest request,
            boolean reprocessed
    ) {
        events.publish(runId, StreamEventType.RUN_ACCEPTED,
                reprocessed
                        ? java.util.Map.of("requestedMode", request.mode(), "reprocessed", true)
                        : java.util.Map.of("requestedMode", request.mode()));
        var task = new FutureTask<Void>(() -> {
            execute(runId, organizationId, userId, conversationId, request);
            return null;
        });
        activeRuns.put(runId, task);
        executor.execute(task);
        return new RunAcceptedResponse(runId, request.mode(), "/api/v1/runs/" + runId + "/chat-events");
    }

    public RunAcceptedResponse startAgenticRetrieval(
            UUID organizationId,
            UUID userId,
            UUID conversationId,
            CreateRunRequest request
    ) {
        var deepRequest = new CreateRunRequest(
                request.query(), RunMode.DEEP, request.scope(), request.filters(), request.modelProfileId());
        var runId = UUID.randomUUID();
        runRecords.create(runId, organizationId, userId, conversationId, deepRequest);
        events.publish(runId, StreamEventType.RUN_ACCEPTED,
                java.util.Map.of("requestedMode", RunMode.DEEP, "answerGenerationSkipped", true));
        var task = new FutureTask<Void>(() -> {
            executeAgenticRetrieval(runId, organizationId, userId, conversationId, deepRequest);
            return null;
        });
        activeRuns.put(runId, task);
        executor.execute(task);
        return new RunAcceptedResponse(runId, RunMode.DEEP, "/api/v1/runs/" + runId + "/events");
    }

    public boolean cancel(UUID runId) {
        var future = activeRuns.remove(runId);
        runRecords.cancel(runId);
        var cancelled = future != null && !future.isDone() && future.cancel(true);
        if (cancelled || future == null) {
            events.publish(runId, StreamEventType.RUN_CANCELLED,
                    java.util.Map.of("reason", "cancelled-by-user"));
        }
        return cancelled || future == null;
    }


    private void execute(
            UUID runId,
            UUID organizationId,
            UUID userId,
            UUID conversationId,
            CreateRunRequest request
    ) {
        RunMode selectedMode = request.mode() == RunMode.FAST ? RunMode.FAST : RunMode.DEEP;
        try {
            // AUTO 路由前启动端到端计时。
            runRecords.markRouting(runId);
            var routePlan = selectPlan(organizationId, userId, request);
            var selection = routePlan.selection();
            var selected = selection.mode();
            selectedMode = selected;
            runRecords.markRunning(runId, selected);
            if (selection.classifiedByModel()) {
                events.publish(runId, StreamEventType.INTENT_CLASSIFIED,
                        java.util.Map.of("selected", selected, "reason", selection.reason(), "classifier", "structured-llm"));
            }
            var routePayload = new java.util.LinkedHashMap<String, Object>();
            routePayload.put("requested", request.mode());
            routePayload.put("selected", selected);
            routePayload.put("reason", selection.reason());
            routePayload.put("routeDecisionSource", selection.decisionSource());
            routePayload.put("signals", selection.signals());
            if (selection.routerProfile() != null) routePayload.put("routerProfile", selection.routerProfile());
            if (selection.titleHitCount() >= 0) routePayload.put("titleHitCount", selection.titleHitCount());
            if (routePlan.preflight() != null) {
                routePayload.put("preflightCandidateCount", routePlan.preflight().fused().size());
                routePayload.put("preflightLatencyMs", routePlan.preflight().latencyMs());
                routePayload.put("preflightTop5", routePlan.preflight().fused().stream().limit(5)
                        .map(hit -> java.util.Map.<String, Object>of(
                                "documentId", hit.documentId(),
                                "documentTitle", hit.documentTitle() == null ? "" : hit.documentTitle(),
                                "score", hit.score()))
                        .toList());
            }
            events.publish(runId, StreamEventType.ROUTE_SELECTED, java.util.Map.copyOf(routePayload));
            var answer = telemetry.observe("rag.run", java.util.Map.of(
                    "mode", selected.name(), "requested_mode", request.mode().name()),
                    () -> selected == RunMode.DEEP
                            ? deepPipeline.execute(runId, conversationId, organizationId, userId, request, true)
                            : fastPipeline.execute(runId, conversationId, organizationId, userId, request,
                                    routePlan.preflight()));
            runRecords.complete(runId);
            events.publish(runId, StreamEventType.RUN_COMPLETED,
                    java.util.Map.of("answerLength", answer.length(), "selectedMode", selected));
        } catch (CancellationException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (Thread.currentThread().isInterrupted()) return;
            var message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (selectedMode == RunMode.DEEP) {
                runRecords.fail(runId, message, stopReason(exception));
            } else {
                runRecords.fail(runId, message);
            }
            events.publish(runId, StreamEventType.RUN_FAILED, java.util.Map.of("message", message));
        } finally {
            activeRuns.remove(runId);
        }
    }

    private void executeAgenticRetrieval(
            UUID runId,
            UUID organizationId,
            UUID userId,
            UUID conversationId,
            CreateRunRequest request
    ) {
        try {
            runRecords.markRunning(runId, RunMode.DEEP);
            events.publish(runId, StreamEventType.ROUTE_SELECTED,
                    java.util.Map.of("requested", RunMode.DEEP, "selected", RunMode.DEEP,
                            "reason", "agentic-retrieval-evaluation"));
            telemetry.observe("rag.run", java.util.Map.of(
                    "mode", RunMode.DEEP.name(),
                    "requested_mode", RunMode.DEEP.name(),
                    "answer_generation", "skipped"),
                    () -> deepPipeline.execute(
                            runId, conversationId, organizationId, userId, request, false));
            runRecords.complete(runId);
            events.publish(runId, StreamEventType.RUN_COMPLETED,
                    java.util.Map.of("answerLength", 0, "selectedMode", RunMode.DEEP,
                            "answerGenerationSkipped", true));
        } catch (CancellationException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (Thread.currentThread().isInterrupted()) return;
            var message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            runRecords.fail(runId, message, stopReason(exception));
            events.publish(runId, StreamEventType.RUN_FAILED,
                    java.util.Map.of("message", message,
                            "answerGenerationSkipped", true));
        } finally {
            activeRuns.remove(runId);
        }
    }

    public Selection selectMode(UUID organizationId, CreateRunRequest request) {
        return selectMode(organizationId, null, request);
    }

    public Selection selectMode(UUID organizationId, UUID userId, CreateRunRequest request) {
        return selectPlan(organizationId, userId, request).selection();
    }

    private RoutePlan selectPlan(UUID organizationId, UUID userId, CreateRunRequest request) {
        if (request.mode() != RunMode.AUTO) {
            return new RoutePlan(new Selection(request.mode(), "user-override", false,
                    "USER_OVERRIDE", java.util.List.of(), null, -1), null);
        }
        var initial = autoModeRouter.route(request.query());
        if (!autoModeRouter.canUseRetrievalEvidence(initial)) {
            return new RoutePlan(selection(initial, "HEURISTIC"), null);
        }
        try {
            var preflight = fastPipeline.prepareRouting(organizationId, userId, request);
            var decision = autoModeRouter.route(request.query(), preflight.fused());
            return new RoutePlan(selection(decision, "HEURISTIC"), preflight);
        } catch (RuntimeException failure) {
            return new RoutePlan(selection(autoModeRouter.retrievalFailure(), "FALLBACK"), null);
        }
    }

    private Selection selection(AutoModeRouter.Decision decision, String source) {
        return new Selection(decision.mode(), decision.reasonCode(), false, source, decision.signals(),
                decision.profile().name(), decision.titleHitCount());
    }

    private String stopReason(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            var message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || message.contains("deadline") || message.contains("超时") || message.contains("timeout")) {
                return "DEADLINE_EXCEEDED";
            }
            if (message.contains("budget exhausted") || message.contains("预算不足")
                    || message.contains("budget infeasible")) {
                return "BUDGET_INFEASIBLE";
            }
        }
        return "SYSTEM_FAILURE";
    }

    public record Selection(
            RunMode mode,
            String reason,
            boolean classifiedByModel,
            String decisionSource,
            java.util.List<String> signals,
            String routerProfile,
            int titleHitCount
    ) {
        public Selection(RunMode mode, String reason, boolean classifiedByModel) {
            this(mode, reason, classifiedByModel,
                    classifiedByModel
                            ? "LLM"
                            : "router-fallback-deep".equals(reason) ? "FALLBACK" : "HEURISTIC",
                    java.util.List.of(), null, -1);
        }

        public Selection(
                RunMode mode,
                String reason,
                boolean classifiedByModel,
                String decisionSource,
                java.util.List<String> signals
        ) {
            this(mode, reason, classifiedByModel, decisionSource, signals, null, -1);
        }

        public Selection {
            signals = signals == null ? java.util.List.of() : java.util.List.copyOf(signals);
            if (titleHitCount < -1) throw new IllegalArgumentException("titleHitCount must be >= -1");
        }
    }

    private record RoutePlan(Selection selection, FastRagPipeline.RoutingPreflight preflight) {
    }
}
