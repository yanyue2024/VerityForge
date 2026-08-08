package com.yanyue.rag.domain.agent;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AgentRunState {
    private static final Map<AgentStage, Set<AgentStage>> TRANSITIONS = transitions();

    private final UUID runId;
    private AgentStage stage;
    private AgentBudget budget;
    private final Instant createdAt;
    private Instant updatedAt;

    public AgentRunState(UUID runId, AgentStage stage, AgentBudget budget, Instant createdAt, Instant updatedAt) {
        this.runId = Objects.requireNonNull(runId);
        this.stage = Objects.requireNonNull(stage);
        this.budget = Objects.requireNonNull(budget);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static AgentRunState start(UUID runId, Instant now) {
        return new AgentRunState(runId, AgentStage.ROUTE, AgentBudget.defaults(now), now, now);
    }

    public void moveTo(AgentStage target, Instant now) {
        if (!TRANSITIONS.getOrDefault(stage, Set.of()).contains(target)) {
            throw new IllegalStateException("Illegal agent transition from " + stage + " to " + target);
        }
        stage = target;
        updatedAt = Objects.requireNonNull(now);
    }

    public void useRound() { budget = budget.consumeRound(); }
    public void useSearch() { budget = budget.consumeSearch(); }
    public void useSearches(int amount) { budget = budget.consumeSearches(amount); }
    public void useDeepRead() { budget = budget.consumeDeepRead(); }

    private static Map<AgentStage, Set<AgentStage>> transitions() {
        var map = new EnumMap<AgentStage, Set<AgentStage>>(AgentStage.class);
        map.put(AgentStage.ROUTE, EnumSet.of(AgentStage.PLAN, AgentStage.FAILED, AgentStage.CANCELLED));
        map.put(AgentStage.PLAN, EnumSet.of(AgentStage.RETRIEVE, AgentStage.FAILED, AgentStage.CANCELLED));
        map.put(AgentStage.RETRIEVE, EnumSet.of(AgentStage.DEEP_READ, AgentStage.FAILED, AgentStage.CANCELLED));
        map.put(AgentStage.DEEP_READ, EnumSet.of(
                AgentStage.FACT_LEDGER, AgentStage.COVERAGE_JUDGE, AgentStage.FAILED, AgentStage.CANCELLED));
        map.put(AgentStage.FACT_LEDGER, EnumSet.of(AgentStage.COVERAGE_JUDGE, AgentStage.FAILED, AgentStage.CANCELLED));
        map.put(AgentStage.COVERAGE_JUDGE, EnumSet.of(AgentStage.GAP_SEARCH, AgentStage.SYNTHESIZE, AgentStage.FAILED, AgentStage.CANCELLED));
        map.put(AgentStage.GAP_SEARCH, EnumSet.of(AgentStage.RETRIEVE, AgentStage.SYNTHESIZE, AgentStage.FAILED, AgentStage.CANCELLED));
        map.put(AgentStage.SYNTHESIZE, EnumSet.of(AgentStage.VERIFY, AgentStage.FAILED, AgentStage.CANCELLED));
        map.put(AgentStage.VERIFY, EnumSet.of(AgentStage.COMPLETED, AgentStage.FAILED, AgentStage.CANCELLED));
        return Map.copyOf(map);
    }

    public UUID runId() { return runId; }
    public AgentStage stage() { return stage; }
    public AgentBudget budget() { return budget; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
