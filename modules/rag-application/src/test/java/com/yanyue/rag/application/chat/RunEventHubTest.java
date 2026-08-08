package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanyue.rag.contract.chat.StreamEvent;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.port.RunEventPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunEventHubTest {
    @Test
    void failedClientListenerDoesNotInterruptRunEventPublishing() {
        var runId = UUID.randomUUID();
        var sequence = new AtomicInteger();
        var store = mock(RunEventPort.class);
        when(store.append(eq(runId), any(), any())).thenAnswer(invocation -> new StreamEvent(
                UUID.randomUUID(), runId, sequence.incrementAndGet(), invocation.getArgument(1),
                Instant.parse("2026-08-03T00:00:00Z"), invocation.getArgument(2)));
        var hub = new RunEventHub(store);
        var failedCalls = new AtomicInteger();
        var delivered = new ArrayList<StreamEvent>();
        hub.subscribe(runId, ignored -> {
            failedCalls.incrementAndGet();
            throw new IllegalStateException("AsyncContext is already invalid");
        });
        hub.subscribe(runId, delivered::add);

        assertDoesNotThrow(() -> hub.publish(runId, StreamEventType.ROUTE_SELECTED, "deep"));
        assertDoesNotThrow(() -> hub.publish(runId, StreamEventType.EVIDENCE_JUDGE_STARTED, "judge"));

        assertEquals(1, failedCalls.get());
        assertEquals(2, delivered.size());
        assertEquals(StreamEventType.EVIDENCE_JUDGE_STARTED, delivered.getLast().type());
    }

    @Test
    void terminalEventRemovesLiveListeners() {
        var runId = UUID.randomUUID();
        var sequence = new AtomicInteger();
        var store = mock(RunEventPort.class);
        when(store.append(eq(runId), any(), any())).thenAnswer(invocation -> new StreamEvent(
                UUID.randomUUID(), runId, sequence.incrementAndGet(), invocation.getArgument(1),
                Instant.parse("2026-08-03T00:00:00Z"), invocation.getArgument(2)));
        var hub = new RunEventHub(store);
        var delivered = new ArrayList<StreamEvent>();
        hub.subscribe(runId, delivered::add);

        hub.publish(runId, StreamEventType.RUN_COMPLETED, "done");
        hub.publish(runId, StreamEventType.RUN_FAILED, "late-event");

        assertEquals(1, delivered.size());
        assertEquals(StreamEventType.RUN_COMPLETED, delivered.getFirst().type());
    }
}
