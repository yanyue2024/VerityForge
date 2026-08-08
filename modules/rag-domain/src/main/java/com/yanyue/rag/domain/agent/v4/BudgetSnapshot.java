package com.yanyue.rag.domain.agent.v4;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public record BudgetSnapshot(
        Map<BudgetDimension, Long> used,
        Map<BudgetDimension, Long> remaining,
        Instant deadline,
        AgentStopReason stopReason
) {
    public BudgetSnapshot {
        used = immutableEnumMap(used);
        remaining = immutableEnumMap(remaining);
    }

    private static Map<BudgetDimension, Long> immutableEnumMap(Map<BudgetDimension, Long> values) {
        var copy = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
        copy.putAll(values);
        return Map.copyOf(copy);
    }
}
