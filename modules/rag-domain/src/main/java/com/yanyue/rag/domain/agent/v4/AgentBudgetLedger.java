package com.yanyue.rag.domain.agent.v4;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AgentBudgetLedger {
    private static final java.util.Set<BudgetDimension> RECONCILABLE_TOKEN_DIMENSIONS = java.util.Set.of(
            BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN,
            BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN
    );

    private final com.yanyue.rag.domain.agent.AgentBudgetLimits limits;
    private final Instant startedAt;
    private final Instant deadline;
    private final Map<UUID, BudgetReservation> reservations = new LinkedHashMap<>();
    private final Map<String, UUID> actionIndex = new LinkedHashMap<>();
    private AgentStopReason stopReason;

    public AgentBudgetLedger(com.yanyue.rag.domain.agent.AgentBudgetLimits limits, Instant startedAt) {
        this.limits = V4Validation.required(limits, "limits");
        this.startedAt = V4Validation.required(startedAt, "startedAt");
        this.deadline = startedAt.plus(limits.runDeadline());
    }

    public static AgentBudgetLedger restore(
            com.yanyue.rag.domain.agent.AgentBudgetLimits limits,
            Instant startedAt,
            java.util.Collection<BudgetReservation> persistedReservations
    ) {
        var ledger = new AgentBudgetLedger(limits, startedAt);
        for (var reservation : persistedReservations) {
            if (ledger.reservations.putIfAbsent(reservation.reservationId(), reservation) != null
                    || ledger.actionIndex.putIfAbsent(reservation.actionKey(), reservation.reservationId()) != null) {
                throw new IllegalArgumentException("恢复预算包含重复 Reservation 或 actionKey");
            }
        }
        if (ledger.exceedsAnyLimit()) {
            ledger.stopReason = AgentStopReason.BUDGET_EXHAUSTED;
        }
        return ledger;
    }

    /**
     * 同一个 actionKey 的重复预留返回原记录，保证恢复重放不会重复占用预算。
     */
    public synchronized BudgetReservation reserve(String actionKey,
                                                   Map<BudgetDimension, Long> requested,
                                                   Instant now) {
        ensureActive(now);
        V4Validation.requiredText(actionKey, "actionKey");
        var usage = normalizedUsage(requested);
        var existingId = actionIndex.get(actionKey);
        if (existingId != null) {
            var existing = reservations.get(existingId);
            if (!existing.usage().equals(usage)) {
                throw new IllegalStateException("actionKey is already reserved with different usage");
            }
            return existing;
        }

        var current = aggregateUsage();
        for (var entry : usage.entrySet()) {
            long maximum = maximum(entry.getKey());
            long used = current.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() > maximum - used) {
                throw new IllegalStateException("budget exhausted for " + entry.getKey());
            }
        }

        var reservation = new BudgetReservation(UUID.randomUUID(), actionKey, usage,
                BudgetReservationStatus.RESERVED, now, now);
        reservations.put(reservation.reservationId(), reservation);
        actionIndex.put(actionKey, reservation.reservationId());
        return reservation;
    }

    public synchronized BudgetReservation markDispatched(UUID reservationId, Instant now) {
        ensureActive(now);
        var reservation = requireReservation(reservationId);
        if (reservation.status() == BudgetReservationStatus.DISPATCHED) {
            return reservation;
        }
        if (reservation.status() != BudgetReservationStatus.RESERVED) {
            throw new IllegalStateException("only a reserved action can be dispatched");
        }
        return replace(reservation.withStatus(BudgetReservationStatus.DISPATCHED, now));
    }

    public synchronized BudgetReservation succeed(UUID reservationId,
                                                  Map<BudgetDimension, Long> actualTokenUsage,
                                                  Instant now) {
        return finish(reservationId, actualTokenUsage, BudgetReservationStatus.SUCCEEDED, now);
    }

    public synchronized BudgetReservation fail(UUID reservationId,
                                               Map<BudgetDimension, Long> actualTokenUsage,
                                               Instant now) {
        return finish(reservationId, actualTokenUsage, BudgetReservationStatus.FAILED, now);
    }

    public synchronized BudgetReservation release(UUID reservationId, Instant now) {
        var reservation = requireReservation(reservationId);
        if (reservation.status() == BudgetReservationStatus.RELEASED) {
            return reservation;
        }
        if (reservation.status() != BudgetReservationStatus.RESERVED) {
            throw new IllegalStateException("dispatched budget cannot be released");
        }
        return replace(reservation.withStatus(BudgetReservationStatus.RELEASED, now));
    }

    public synchronized void stop(AgentStopReason reason) {
        V4Validation.required(reason, "reason");
        if (stopReason != null && stopReason != reason) {
            throw new IllegalStateException("ledger already stopped with " + stopReason);
        }
        stopReason = reason;
    }

    public synchronized boolean canReserve(Map<BudgetDimension, Long> requested, Instant now) {
        if (stopReason != null || !now.isBefore(deadline)) {
            return false;
        }
        var usage = normalizedUsage(requested);
        var current = aggregateUsage();
        return usage.entrySet().stream().allMatch(entry ->
                entry.getValue() <= maximum(entry.getKey()) - current.getOrDefault(entry.getKey(), 0L));
    }

    public synchronized BudgetSnapshot snapshot() {
        var used = aggregateUsage();
        var remaining = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
        for (var dimension : BudgetDimension.values()) {
            remaining.put(dimension, Math.max(0, maximum(dimension) - used.getOrDefault(dimension, 0L)));
        }
        return new BudgetSnapshot(used, remaining, deadline, stopReason);
    }

    public synchronized Optional<BudgetReservation> findByActionKey(String actionKey) {
        var id = actionIndex.get(actionKey);
        return id == null ? Optional.empty() : Optional.of(reservations.get(id));
    }

    public synchronized Map<UUID, BudgetReservation> reservations() {
        return Map.copyOf(reservations);
    }

    public com.yanyue.rag.domain.agent.AgentBudgetLimits limits() {
        return limits;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant deadline() {
        return deadline;
    }

    private BudgetReservation finish(UUID reservationId,
                                     Map<BudgetDimension, Long> actualTokenUsage,
                                     BudgetReservationStatus status,
                                     Instant now) {
        var reservation = requireReservation(reservationId);
        if (reservation.status() == status) {
            return reservation;
        }
        if (reservation.status() != BudgetReservationStatus.DISPATCHED) {
            throw new IllegalStateException("only a dispatched action can finish");
        }
        var reconciled = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
        reconciled.putAll(reservation.usage());
        if (actualTokenUsage != null) {
            actualTokenUsage.forEach((dimension, amount) -> {
                if (!RECONCILABLE_TOKEN_DIMENSIONS.contains(dimension) || amount == null || amount < 0) {
                    throw new IllegalArgumentException("only non-negative token usage can be reconciled");
                }
                if (!reconciled.containsKey(dimension) && amount > 0) {
                    throw new IllegalArgumentException("actual token usage was not reserved");
                }
                if (amount == 0) {
                    reconciled.remove(dimension);
                } else {
                    reconciled.put(dimension, amount);
                }
            });
        }
        var updated = reservation.withUsageAndStatus(reconciled, status, now);
        replace(updated);
        if (exceedsAnyLimit()) {
            stopReason = AgentStopReason.BUDGET_EXHAUSTED;
        }
        return updated;
    }

    private void ensureActive(Instant now) {
        V4Validation.required(now, "now");
        if (stopReason != null) {
            throw new IllegalStateException("ledger is stopped: " + stopReason);
        }
        if (!now.isBefore(deadline)) {
            stopReason = AgentStopReason.DEADLINE_EXCEEDED;
            throw new IllegalStateException("run deadline exceeded");
        }
    }

    private BudgetReservation requireReservation(UUID id) {
        var reservation = reservations.get(id);
        if (reservation == null) {
            throw new IllegalArgumentException("unknown reservation " + id);
        }
        return reservation;
    }

    private BudgetReservation replace(BudgetReservation reservation) {
        reservations.put(reservation.reservationId(), reservation);
        return reservation;
    }

    private EnumMap<BudgetDimension, Long> normalizedUsage(Map<BudgetDimension, Long> requested) {
        V4Validation.required(requested, "requested");
        var usage = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
        requested.forEach((dimension, amount) -> {
            V4Validation.required(dimension, "budget dimension");
            if (amount == null || amount < 1) {
                throw new IllegalArgumentException("requested budget must be positive");
            }
            usage.merge(dimension, amount, Long::sum);
        });
        if (usage.isEmpty()) {
            throw new IllegalArgumentException("requested budget must not be empty");
        }
        return usage;
    }

    private EnumMap<BudgetDimension, Long> aggregateUsage() {
        var result = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
        reservations.values().stream()
                .filter(BudgetReservation::consumesBudget)
                .forEach(reservation -> reservation.usage().forEach(
                        (dimension, amount) -> result.merge(dimension, amount, Long::sum)));
        return result;
    }

    private boolean exceedsAnyLimit() {
        return aggregateUsage().entrySet().stream().anyMatch(entry -> entry.getValue() > maximum(entry.getKey()));
    }

    private long maximum(BudgetDimension dimension) {
        return limits.maximum(dimension);
    }
}
