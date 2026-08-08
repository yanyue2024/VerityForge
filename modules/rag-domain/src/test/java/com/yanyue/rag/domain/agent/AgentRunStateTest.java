package com.yanyue.rag.domain.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRunStateTest {
    @Test
    void lightweightEvidenceFlowCanMoveDirectlyFromDeepReadToCoverageJudge() {
        var now = Instant.parse("2026-07-21T00:00:00Z");
        var state = AgentRunState.start(UUID.randomUUID(), now);

        state.moveTo(AgentStage.PLAN, now);
        state.moveTo(AgentStage.RETRIEVE, now);
        state.moveTo(AgentStage.DEEP_READ, now);
        state.moveTo(AgentStage.COVERAGE_JUDGE, now);

        assertEquals(AgentStage.COVERAGE_JUDGE, state.stage());
    }

    @Test
    void searchBudgetCanReserveBothPhysicalCallsForHybridRetrieval() {
        var now = Instant.parse("2026-07-21T00:00:00Z");
        var budget = new AgentBudget(2, 2, 3, 2, 2, Duration.ofMinutes(1), 0, 0, 0, now);

        var afterHybrid = budget.consumeSearches(2);

        assertEquals(2, afterHybrid.searchesUsed());
        assertEquals(3, afterHybrid.consumeSearch().searchesUsed());
        assertThrows(IllegalStateException.class, () -> afterHybrid.consumeSearches(2));
    }
}
