package com.yanyue.rag.application.chat.v7;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;
import com.yanyue.rag.domain.agent.v8.AgenticV8Limits;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GoalResearchServiceV7Test {
    @Test
    void v8Rerank输入应包含文档标题而v7保持历史行为() {
        var hit = new RetrievalHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "管理系统资源", "CPU份额与操作步骤", 0.9, List.of("keyword"));

        assertEquals("文档标题：管理系统资源\nCPU份额与操作步骤",
                GoalResearchServiceV7.rerankText(hit, AgenticV8Limits.defaults()));
        assertEquals("CPU份额与操作步骤",
                GoalResearchServiceV7.rerankText(hit, AgenticV5Limits.v7Defaults()));
    }

    @Test
    void 补充检索必须为最终回答保留配置的超时时间() {
        var startedAt = Instant.parse("2026-08-04T08:00:00Z");
        var ledger = new AgentBudgetLedger(AgenticV8Limits.defaults(), startedAt);

        var optionalDeadline = AgenticRagV7Pipeline.optionalWorkDeadline(ledger, 120);

        assertEquals(startedAt.plusSeconds(117), optionalDeadline);
        assertEquals(ledger.deadline().minusSeconds(123), optionalDeadline);
    }
}
