package com.yanyue.rag.api.chat;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.chat.RunCoordinator;
import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.RunAcceptedResponse;
import com.yanyue.rag.contract.chat.StreamEvent;
import com.yanyue.rag.contract.chat.StreamEventType;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class RunController {
    private final RunCoordinator coordinator;
    private final RunEventHub eventHub;
    private final DSLContext dsl;
    private final RunTraceService traces;

    public RunController(RunCoordinator coordinator, RunEventHub eventHub, DSLContext dsl,
                         RunTraceService traces) {
        this.coordinator = coordinator;
        this.eventHub = eventHub;
        this.dsl = dsl;
        this.traces = traces;
    }

    @PostMapping("/conversations/{conversationId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAcceptedResponse start(@AuthenticationPrincipal AuthenticatedUser user,
                                     @PathVariable UUID conversationId,
                                     @Valid @RequestBody CreateRunRequest request) {
        requireOwnedConversation(user, conversationId);
        return coordinator.start(user.organizationId(), user.userId(), conversationId, request);
    }

    @PostMapping("/runs/{runId}/reprocess")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAcceptedResponse reprocess(@AuthenticationPrincipal AuthenticatedUser user,
                                         @PathVariable UUID runId) {
        requireOwnedRun(user, runId);
        return coordinator.reprocess(user.organizationId(), user.userId(), runId);
    }

    @DeleteMapping("/runs/{runId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID runId) {
        requireOwnedRun(user, runId);
        coordinator.cancel(runId);
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal AuthenticatedUser user,
                             @PathVariable UUID runId,
                             @RequestParam(defaultValue = "0") long after) {
        requireOwnedRun(user, runId);
        return openStream(runId, after, false);
    }

    @GetMapping(value = "/runs/{runId}/chat-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatEvents(@AuthenticationPrincipal AuthenticatedUser user,
                                 @PathVariable UUID runId,
                                 @RequestParam(defaultValue = "0") long after) {
        requireOwnedRun(user, runId);
        return openStream(runId, after, true);
    }

    @GetMapping("/runs/{runId}/trace")
    public com.yanyue.rag.contract.chat.RunTraceView trace(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID runId
    ) {
        requireOwnedRun(user, runId);
        return traces.trace(runId);
    }

    private SseEmitter openStream(UUID runId, long after, boolean chatStream) {
        var emitter = new SseEmitter(65_000L);
        var lastSent = new AtomicLong(after);
        var subscription = new AtomicReference<AutoCloseable>();
        var terminated = new AtomicBoolean();

        emitter.onCompletion(() -> terminate(terminated, subscription));
        emitter.onTimeout(() -> complete(emitter, terminated, subscription));
        emitter.onError(ignored -> terminate(terminated, subscription));

        try {
            var handle = eventHub.replayThenSubscribe(
                    runId,
                    after,
                    event -> {
                        var outgoing = chatStream ? chatEvent(event) : event;
                        if (outgoing != null) {
                            sendIfNew(emitter, outgoing, lastSent, subscription, terminated);
                        }
                    }
            );
            subscription.set(handle);
            if (terminated.get()) close(subscription);
        } catch (Exception exception) {
            completeWithError(emitter, terminated, subscription, exception);
        }
        return emitter;
    }

    private StreamEvent chatEvent(StreamEvent event) {
        if (event.type() == StreamEventType.ANSWER_DELTA
                || event.type() == StreamEventType.ANSWER_REPLACED
                || event.type() == StreamEventType.CITATION) {
            return event;
        }
        if (event.type() == StreamEventType.ANSWER_MODE_SELECTED) {
            var source = payload(event.payload());
            var safe = new LinkedHashMap<String, Object>();
            copy(source, safe, "mode");
            copy(source, safe, "retrievalHealth");
            copy(source, safe, "evidenceCount");
            return withPayload(event, safe);
        }
        if (event.type() == StreamEventType.RUN_COMPLETED) {
            return withPayload(event, Map.of("completed", true, "trace", traces.trace(event.runId())));
        }
        if (event.type() == StreamEventType.RUN_CANCELLED) {
            return withPayload(event, Map.of(
                    "message", "已停止本次处理",
                    "trace", traces.trace(event.runId())));
        }
        if (event.type() == StreamEventType.RUN_FAILED) {
            return withPayload(event, Map.of(
                    "message", "暂时无法完成本次回答，请重新处理。",
                    "trace", traces.trace(event.runId())));
        }
        if (!traces.shouldRefresh(event.type())) return null;
        return new StreamEvent(event.eventId(), event.runId(), event.sequence(),
                StreamEventType.TRACE_UPDATED, event.timestamp(), traces.trace(event.runId()));
    }

    private StreamEvent withPayload(StreamEvent event, Object payload) {
        return new StreamEvent(event.eventId(), event.runId(), event.sequence(), event.type(), event.timestamp(), payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.get(key) != null) target.put(key, source.get(key));
    }

    private void sendIfNew(SseEmitter emitter, StreamEvent event, AtomicLong lastSent,
                           AtomicReference<AutoCloseable> subscription, AtomicBoolean terminated) {
        if (terminated.get()) return;
        if (event.sequence() <= lastSent.get()) return;
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.type().name())
                    .data(event, MediaType.APPLICATION_JSON));
            lastSent.set(event.sequence());
            if (isTerminal(event.type())) {
                complete(emitter, terminated, subscription);
            }
        } catch (IOException | IllegalStateException exception) {
            // A failed send means the servlet container already owns the error lifecycle.
            // Detach this client without touching AsyncContext again; the run continues.
            terminate(terminated, subscription);
        }
    }

    private void complete(SseEmitter emitter, AtomicBoolean terminated,
                          AtomicReference<AutoCloseable> subscription) {
        if (!terminate(terminated, subscription)) return;
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // Completion raced with a servlet-container error callback.
        }
    }

    private void completeWithError(SseEmitter emitter, AtomicBoolean terminated,
                                   AtomicReference<AutoCloseable> subscription, Exception exception) {
        if (!terminate(terminated, subscription)) return;
        try {
            emitter.completeWithError(exception);
        } catch (IllegalStateException ignored) {
            // The container may already have completed the AsyncContext.
        }
    }

    private boolean terminate(AtomicBoolean terminated, AtomicReference<AutoCloseable> subscription) {
        if (!terminated.compareAndSet(false, true)) return false;
        close(subscription);
        return true;
    }

    private boolean isTerminal(StreamEventType type) {
        return type == StreamEventType.RUN_COMPLETED || type == StreamEventType.RUN_FAILED
                || type == StreamEventType.RUN_CANCELLED;
    }

    private void close(AtomicReference<AutoCloseable> subscription) {
        var value = subscription.getAndSet(null);
        if (value == null) return;
        try {
            value.close();
        } catch (Exception ignored) {
            // Listener cleanup is best effort after the client disconnects.
        }
    }

    private void requireOwnedRun(AuthenticatedUser user, UUID runId) {
        var exists = dsl.fetchOptional("""
                SELECT 1 FROM rag_run run
                WHERE run.id = ? AND run.organization_id = ?
                  AND (run.created_by = ? OR EXISTS (
                      SELECT 1 FROM app_user actor
                      WHERE actor.id = ? AND actor.organization_id = run.organization_id
                        AND actor.enabled = true AND actor.role = 'ADMIN'
                  ))
                """, runId, user.organizationId(), user.userId(), user.userId()).isPresent();
        if (!exists) throw new IllegalArgumentException("Run not found");
    }

    private void requireOwnedConversation(AuthenticatedUser user, UUID conversationId) {
        var exists = dsl.fetchOptional("""
                SELECT 1 FROM conversation
                WHERE id = ? AND organization_id = ? AND created_by = ?
                  AND conversation_kind = 'USER' AND deleted_at IS NULL
                """, conversationId, user.organizationId(), user.userId()).isPresent();
        if (!exists) throw new IllegalArgumentException("Conversation not found");
    }
}
