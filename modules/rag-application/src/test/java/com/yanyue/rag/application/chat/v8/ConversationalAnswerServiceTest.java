package com.yanyue.rag.application.chat.v8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v8.AgenticV8Limits;
import com.yanyue.rag.domain.model.AssistantProfile;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationalAnswerServiceTest {
    @Test
    void 模型服务连续不可用时应收口为可重新处理的正常消息() {
        var model = mock(StreamingAnswerModelPort.class);
        var memory = mock(ConversationMemoryPort.class);
        var events = mock(RunEventHub.class);
        var now = Instant.parse("2026-08-04T08:00:00Z");
        var service = new ConversationalAnswerService(model, memory, events,
                Clock.fixed(now, ZoneOffset.UTC));
        var profileId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var limits = AgenticV8Limits.defaults();
        var ledger = new AgentBudgetLedger(limits, now);
        when(model.generate(eq(profileId), any(), any(), eq(3)))
                .thenThrow(new IllegalStateException("HTTP 503"));

        var result = service.answer(runId, conversationId, "你好", "你好", profileId,
                assistant(), List.of(), 0.0, 120, ledger, limits,
                ConversationalAnswerService.KnowledgeDemand.GENERAL,
                ConversationalAnswerService.RetrievalHealth.EMPTY);

        assertEquals("TEMPORARILY_UNAVAILABLE", result.answerMode());
        assertEquals("这次暂时没有生成出可靠回复。请稍后点击“重新处理”再次尝试。", result.answer());
        verify(model).generate(eq(profileId), any(), any(), eq(3));
        verify(events).publish(runId, StreamEventType.ANSWER_REPLACED,
                Map.of("text", result.answer()));
        verify(memory).append(conversationId, "user", "你好", runId);
        verify(memory).append(conversationId, "assistant", result.answer(), runId);
    }

    private AssistantProfile assistant() {
        var now = Instant.parse("2026-08-04T08:00:00Z");
        return new AssistantProfile(UUID.randomUUID(), UUID.randomUUID(), 1,
                AssistantProfile.Status.PUBLISHED, "VerityForge", "企业知识助手",
                List.of("回答问题"), "简洁", List.of("不编造"), "", now, now, now, now);
    }
}
