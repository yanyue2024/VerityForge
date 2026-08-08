package com.yanyue.rag.application.chat;

import com.yanyue.rag.contract.chat.StreamEvent;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.port.RunEventPort;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;

@Component
public class RunEventHub {
    private static final Log LOG = LogFactory.getLog(RunEventHub.class);

    private final RunEventPort eventStore;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Consumer<StreamEvent>>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> runLocks = new ConcurrentHashMap<>();

    public RunEventHub(RunEventPort eventStore) {
        this.eventStore = eventStore;
    }

    public StreamEvent publish(UUID runId, StreamEventType type, Object payload) {
        var runLock = lock(runId);
        StreamEvent event;
        synchronized (runLock) {
            event = eventStore.append(runId, type, payload);
            var runListeners = listeners.get(runId);
            if (runListeners != null) {
                for (var listener : runListeners) {
                    try {
                        listener.accept(event);
                    } catch (RuntimeException exception) {
                        runListeners.remove(listener);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Detached failed run event listener for " + runId, exception);
                        }
                    }
                }
                if (runListeners.isEmpty()) listeners.remove(runId, runListeners);
            }
            if (terminal(type)) listeners.remove(runId);
        }
        if (terminal(type)) runLocks.remove(runId, runLock);
        return event;
    }

    public List<StreamEvent> replay(UUID runId, long afterSequence) {
        return eventStore.replay(runId, afterSequence);
    }

    public AutoCloseable subscribe(UUID runId, Consumer<StreamEvent> listener) {
        listeners.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return closeAction(runId, listener);
    }

    public AutoCloseable replayThenSubscribe(UUID runId, long afterSequence, Consumer<StreamEvent> listener) {
        var runLock = lock(runId);
        synchronized (runLock) {
            var replay = eventStore.replay(runId, afterSequence);
            replay.forEach(listener);
            if (!replay.isEmpty() && terminal(replay.getLast().type())) {
                runLocks.remove(runId, runLock);
                return () -> { };
            }
            listeners.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
            return closeAction(runId, listener);
        }
    }

    private AutoCloseable closeAction(UUID runId, Consumer<StreamEvent> listener) {
        return () -> {
            listeners.computeIfPresent(runId, (ignored, current) -> {
                current.remove(listener);
                return current.isEmpty() ? null : current;
            });
        };
    }

    private Object lock(UUID runId) {
        return runLocks.computeIfAbsent(runId, ignored -> new Object());
    }

    private boolean terminal(StreamEventType type) {
        return type == StreamEventType.RUN_COMPLETED || type == StreamEventType.RUN_CANCELLED
                || type == StreamEventType.RUN_FAILED;
    }
}
