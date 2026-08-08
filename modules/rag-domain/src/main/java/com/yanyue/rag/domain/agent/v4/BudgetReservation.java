package com.yanyue.rag.domain.agent.v4;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public record BudgetReservation(
        UUID reservationId,
        String actionKey,
        Map<BudgetDimension, Long> usage,
        BudgetReservationStatus status,
        Instant reservedAt,
        Instant updatedAt
) {
    public BudgetReservation {
        V4Validation.required(reservationId, "reservationId");
        actionKey = V4Validation.requiredText(actionKey, "actionKey");
        V4Validation.required(usage, "usage");
        var copy = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
        usage.forEach((dimension, amount) -> {
            V4Validation.required(dimension, "usage dimension");
            if (amount == null || amount < 1) {
                throw new IllegalArgumentException("reserved usage must be positive");
            }
            copy.put(dimension, amount);
        });
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("reservation usage must not be empty");
        }
        usage = Map.copyOf(copy);
        V4Validation.required(status, "status");
        V4Validation.required(reservedAt, "reservedAt");
        V4Validation.required(updatedAt, "updatedAt");
    }

    BudgetReservation withStatus(BudgetReservationStatus target, Instant now) {
        return new BudgetReservation(reservationId, actionKey, usage, target, reservedAt, now);
    }

    BudgetReservation withUsageAndStatus(Map<BudgetDimension, Long> actualUsage,
                                         BudgetReservationStatus target,
                                         Instant now) {
        return new BudgetReservation(reservationId, actionKey, actualUsage, target, reservedAt, now);
    }

    public boolean consumesBudget() {
        return status != BudgetReservationStatus.RELEASED;
    }
}
