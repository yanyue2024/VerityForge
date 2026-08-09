package com.yanyue.rag.domain.agent;

import com.yanyue.rag.domain.agent.budget.BudgetDimension;
import java.time.Duration;

/**
 * 各运行链路向共享预算账本提供不可变上限。
 */
public interface AgentBudgetLimits {
    Duration runDeadline();

    long maximum(BudgetDimension dimension);
}
