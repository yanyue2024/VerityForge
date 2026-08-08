package com.yanyue.rag.contract.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunTraceView(
        UUID runId,
        RunMode requestedMode,
        RunMode selectedMode,
        String path,
        String state,
        Instant startedAt,
        Instant firstAnswerAt,
        Instant completedAt,
        Long durationMs,
        boolean traceAvailable,
        String answerMode,
        String retrievalHealth,
        Integer evidenceCount,
        List<RunTraceNodeView> nodes
) {
    public RunTraceView {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
