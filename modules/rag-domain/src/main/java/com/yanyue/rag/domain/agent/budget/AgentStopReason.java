package com.yanyue.rag.domain.agent.budget;

public enum AgentStopReason {
    COMPLETED,
    ZERO_ACCEPTED_EVIDENCE,
    BUDGET_EXHAUSTED,
    DEADLINE_EXCEEDED,
    CANCELLED,
    SYSTEM_FAILURE
}
