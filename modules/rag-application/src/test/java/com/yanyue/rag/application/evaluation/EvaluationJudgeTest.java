package com.yanyue.rag.application.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.contract.evaluation.EvaluationJudgeMode;
import com.yanyue.rag.domain.evaluation.EvaluationCase;
import com.yanyue.rag.domain.model.PipelineConfig;
import com.yanyue.rag.domain.port.EvaluationRepository.CitationEvidence;
import com.yanyue.rag.domain.port.StructuredReasoningModelPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationJudgeTest {
    @Test
    void gradesSemanticAnswerAndCitationEntailmentWithTheResolvedChatProfile() {
        var organizationId = UUID.randomUUID();
        var profileId = UUID.randomUUID();
        var model = mock(StructuredReasoningModelPort.class);
        var configs = mock(PipelineConfigService.class);
        when(configs.resolve(organizationId, null)).thenReturn(config(organizationId, profileId));
        when(model.completeJson(eq(profileId), eq("evaluation-judge"), anyString(), anyString())).thenReturn("""
                {
                  "answer": {
                    "verdict": "CORRECT",
                    "score": 0.94,
                    "reason": "关键事实一致",
                    "missingFacts": [],
                    "unsupportedClaims": []
                  },
                  "citations": {
                    "verdict": "SUPPORTED",
                    "score": 0.88,
                    "reason": "引用直接支持结论",
                    "unsupportedClaims": []
                  }
                }
                """);
        var judge = new EvaluationJudge(model, configs, new ObjectMapper());
        var datasetId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var chunkId = UUID.randomUUID();

        var result = judge.judge(
                organizationId, null, EvaluationJudgeMode.ANSWER_AND_CITATIONS,
                new EvaluationCase(UUID.randomUUID(), datasetId, "恢复顺序是什么？", "先恢复数据库",
                        List.of(documentId), Map.of()),
                "先恢复数据库，再恢复对象存储。[E1]",
                List.of(new CitationEvidence(1, documentId, versionId, chunkId, "先恢复数据库。")));

        assertEquals("COMPLETED", result.get("judgeStatus"));
        assertEquals("CORRECT", result.get("semanticAnswerVerdict"));
        assertEquals(0.94, result.get("semanticAnswerScore"));
        assertEquals("SUPPORTED", result.get("citationEntailmentVerdict"));
        assertEquals(0.88, result.get("citationEntailmentScore"));
        assertEquals(profileId, result.get("judgeModelProfileId"));
        assertEquals("evaluation-judge-v2", result.get("judgePromptVersion"));
    }

    private PipelineConfig config(UUID organizationId, UUID chatProfileId) {
        return new PipelineConfig(
                UUID.randomUUID(), organizationId, "test", "pipeline-test", "prompt-test",
                chatProfileId, UUID.randomUUID(), UUID.randomUUID(),
                30, 30, 40, 20, 8, 8_000, 0.1, 90, true, Instant.EPOCH, Instant.EPOCH);
    }
}
