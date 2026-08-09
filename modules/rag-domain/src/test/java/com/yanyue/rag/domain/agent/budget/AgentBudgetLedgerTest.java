package com.yanyue.rag.domain.agent.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.domain.agent.deep.DeepRagProfiles;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentBudgetLedgerTest {
    private static final Instant START = Instant.parse("2026-07-23T00:00:00Z");

    @Test
    void reservationIsAtomicAndIdempotentByActionKey() {
        var ledger = new AgentBudgetLedger(DeepRagProfiles.finalProfile(), START);
        var request = Map.of(BudgetDimension.PHYSICAL_SEARCH, 2L, BudgetDimension.RERANK_CALL, 1L);

        var first = ledger.reserve("repair-g1", request, START);
        var replayed = ledger.reserve("repair-g1", request, START);

        assertEquals(first.reservationId(), replayed.reservationId());
        assertEquals(2, ledger.snapshot().used().get(BudgetDimension.PHYSICAL_SEARCH));
        assertThrows(IllegalStateException.class,
                () -> ledger.reserve("repair-g1", Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L), START));
    }

    @Test
    void dispatchedFailureKeepsUsageButUnclaimedReservationCanBeReleased() {
        var ledger = new AgentBudgetLedger(DeepRagProfiles.finalProfile(), START);
        var failed = ledger.reserve("search-1", Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L), START);
        ledger.markDispatched(failed.reservationId(), START);
        ledger.fail(failed.reservationId(), Map.of(), START);
        var unused = ledger.reserve("search-2", Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L), START);
        ledger.release(unused.reservationId(), START);

        assertEquals(1, ledger.snapshot().used().get(BudgetDimension.PHYSICAL_SEARCH));
        assertThrows(IllegalStateException.class, () -> ledger.release(failed.reservationId(), START));
    }

    @Test
    void reconcilesTokensWithoutRefundingLogicalAndPhysicalAttempts() {
        var ledger = new AgentBudgetLedger(DeepRagProfiles.finalProfile(), START);
        var reservation = ledger.reserve("model-analysis", Map.of(
                BudgetDimension.REQUEST_ANALYSIS_CALL, 1L,
                BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL, 1L,
                BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT, 1L,
                BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN, 4_000L,
                BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN, 1_800L), START);
        ledger.markDispatched(reservation.reservationId(), START);
        ledger.succeed(reservation.reservationId(), Map.of(
                BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN, 1_200L,
                BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN, 300L), START);

        var used = ledger.snapshot().used();
        assertEquals(1, used.get(BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL));
        assertEquals(1, used.get(BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT));
        assertEquals(1_200, used.get(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN));
        assertEquals(300, used.get(BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN));
    }

    @Test
    void deadlineStopsFurtherDispatchAndReservation() {
        var ledger = new AgentBudgetLedger(DeepRagProfiles.finalProfile(), START);
        var reservation = ledger.reserve("search", Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L), START);
        var deadline = ledger.deadline();

        assertFalse(ledger.canReserve(Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L), deadline));
        assertThrows(IllegalStateException.class, () -> ledger.markDispatched(reservation.reservationId(), deadline));
        assertEquals(AgentStopReason.DEADLINE_EXCEEDED, ledger.snapshot().stopReason());
    }

    @Test
    void restoresStableReservationsWithoutDoubleCharging() {
        var persisted = new BudgetReservation(UUID.randomUUID(), "search:stable",
                Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L), BudgetReservationStatus.SUCCEEDED, START, START);

        var ledger = AgentBudgetLedger.restore(DeepRagProfiles.finalProfile(), START, List.of(persisted));
        var replayed = ledger.reserve("search:stable", Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L), START);

        assertEquals(persisted.reservationId(), replayed.reservationId());
        assertEquals(BudgetReservationStatus.SUCCEEDED, replayed.status());
        assertEquals(1, ledger.snapshot().used().get(BudgetDimension.PHYSICAL_SEARCH));
    }
}
