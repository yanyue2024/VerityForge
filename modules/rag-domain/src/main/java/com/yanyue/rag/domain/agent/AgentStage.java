package com.yanyue.rag.domain.agent;

public enum AgentStage {
    ROUTE,
    PLAN,
    RETRIEVE,
    DEEP_READ,
    FACT_LEDGER,
    COVERAGE_JUDGE,
    GAP_SEARCH,
    SYNTHESIZE,
    VERIFY,
    COMPLETED,
    FAILED,
    CANCELLED
}
