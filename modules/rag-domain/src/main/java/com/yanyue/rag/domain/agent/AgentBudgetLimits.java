package com.yanyue.rag.domain.agent;

import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import java.time.Duration;

/**
 * 不同 Agentic RAG 协议向共享预算账本提供不可变上限。
 */
public interface AgentBudgetLimits {
    Duration runDeadline();

    long maximum(BudgetDimension dimension);
}
