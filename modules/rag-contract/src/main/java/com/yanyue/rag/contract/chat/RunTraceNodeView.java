package com.yanyue.rag.contract.chat;

import java.time.Instant;
import java.util.List;

public record RunTraceNodeView(
        String key,
        String label,
        String status,
        String summary,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        List<RunTraceDetailView> details,
        List<RunTraceGoalView> goals
) {
    public RunTraceNodeView {
        details = details == null ? List.of() : List.copyOf(details);
        goals = goals == null ? List.of() : List.copyOf(goals);
    }
}
