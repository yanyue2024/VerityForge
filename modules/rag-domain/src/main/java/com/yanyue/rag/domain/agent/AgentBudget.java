package com.yanyue.rag.domain.agent;

import java.time.Duration;
import java.time.Instant;

public record AgentBudget(
        int maxRounds,
        int maxSubQuestions,
        int maxSearches,
        int maxDeepReads,
        int maxParallelism,
        Duration timeout,
        int roundsUsed,
        int searchesUsed,
        int deepReadsUsed,
        Instant startedAt
) {
    public AgentBudget {
        if (maxRounds < 1 || maxSubQuestions < 1 || maxSearches < 1 || maxDeepReads < 1 || maxParallelism < 1) {
            throw new IllegalArgumentException("Agent budget limits must be positive");
        }
    }

    public static AgentBudget defaults(Instant now) {
        return new AgentBudget(4, 6, 8, 6, 4, Duration.ofSeconds(120), 0, 0, 0, now);
    }

    public AgentBudget consumeRound() {
        ensureAvailable(roundsUsed, maxRounds, "round");
        return new AgentBudget(maxRounds, maxSubQuestions, maxSearches, maxDeepReads, maxParallelism,
                timeout, roundsUsed + 1, searchesUsed, deepReadsUsed, startedAt);
    }

    public AgentBudget consumeSearch() {
        return consumeSearches(1);
    }

    /**
     * 为一个计划检索任务消耗物理搜索预算。HYBRID 会同时调用关键词与语义
     * 后端，因此调用方可在并发派发前一次预留两个额度。
     */
    public AgentBudget consumeSearches(int amount) {
        if (amount < 1) throw new IllegalArgumentException("Search consumption must be positive");
        if (searchesUsed > maxSearches - amount) {
            throw new IllegalStateException("Agent search budget exhausted");
        }
        return new AgentBudget(maxRounds, maxSubQuestions, maxSearches, maxDeepReads, maxParallelism,
                timeout, roundsUsed, searchesUsed + amount, deepReadsUsed, startedAt);
    }

    public AgentBudget consumeDeepRead() {
        ensureAvailable(deepReadsUsed, maxDeepReads, "deep read");
        return new AgentBudget(maxRounds, maxSubQuestions, maxSearches, maxDeepReads, maxParallelism,
                timeout, roundsUsed, searchesUsed, deepReadsUsed + 1, startedAt);
    }

    public boolean timedOut(Instant now) {
        return !now.isBefore(startedAt.plus(timeout));
    }

    public boolean exhausted() {
        return roundsUsed >= maxRounds || searchesUsed >= maxSearches || deepReadsUsed >= maxDeepReads;
    }

    private static void ensureAvailable(int used, int maximum, String resource) {
        if (used >= maximum) {
            throw new IllegalStateException("Agent " + resource + " budget exhausted");
        }
    }
}
