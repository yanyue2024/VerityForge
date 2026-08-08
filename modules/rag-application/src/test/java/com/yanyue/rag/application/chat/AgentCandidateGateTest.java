package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentCandidateGateTest {
    private final AgentCandidateGate gate = new AgentCandidateGate();

    @Test
    void keepsOnlyTheBestCandidateWhenEveryScoreFallsBelowTheAgentThreshold() {
        var candidates = List.of(hit("第一"), hit("第二"));

        var selected = gate.select(candidates, List.of(
                new RerankModelPort.RerankScore(1, 0.01),
                new RerankModelPort.RerankScore(0, 0.03)
        ), 0.05);

        assertEquals(1, selected.size());
        assertEquals("第一", selected.getFirst().text());
        assertTrue(selected.getFirst().sources().contains("rerank-threshold-fallback"));
    }

    @Test
    void preservesAllCandidatesThatPassTheThresholdInScoreOrder() {
        var candidates = List.of(hit("第一"), hit("第二"), hit("第三"));

        var selected = gate.select(candidates, List.of(
                new RerankModelPort.RerankScore(2, 0.6),
                new RerankModelPort.RerankScore(0, 0.9),
                new RerankModelPort.RerankScore(1, 0.02)
        ), 0.05);

        assertEquals(List.of("第一", "第三"), selected.stream().map(RetrievalHit::text).toList());
    }

    private RetrievalHit hit(String text) {
        return new RetrievalHit(UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), "文档", text,
                0, List.of("rrf"));
    }
}
