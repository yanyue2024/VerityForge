package com.yanyue.rag.domain.agent.budget;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
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
        Objects.requireNonNull(reservationId, "reservationId");
        if (actionKey == null || actionKey.isBlank()) {
            throw new IllegalArgumentException("actionKey must not be blank");
        }
        actionKey = actionKey.strip();
        Objects.requireNonNull(usage, "usage");
        var copy = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
        usage.forEach((dimension, amount) -> {
            Objects.requireNonNull(dimension, "usage dimension");
            if (amount == null || amount < 1) {
                throw new IllegalArgumentException("reserved usage must be positive");
            }
            copy.put(dimension, amount);
        });
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("reservation usage must not be empty");
        }
        usage = Map.copyOf(copy);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reservedAt, "reservedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
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
