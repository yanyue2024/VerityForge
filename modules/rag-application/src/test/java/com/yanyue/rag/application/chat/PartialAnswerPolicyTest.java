package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.EvidenceItem;
import com.yanyue.rag.domain.agent.QuestionPlan;
import com.yanyue.rag.domain.agent.SubQuestion;
import com.yanyue.rag.domain.agent.SubQuestionCoverage;
import com.yanyue.rag.domain.agent.SupportedSurface;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartialAnswerPolicyTest {
    private final PartialAnswerPolicy policy = new PartialAnswerPolicy();

    @Test
    void acceptsOnlyJudgeSupportedDeepReadEvidenceForEveryQuestion() {
        var runId = UUID.randomUUID();
        var question = new SubQuestion(UUID.randomUUID(), "ZX-100 备案要求", List.of("原文"), 5);
        var evidence = evidence(question.id(), true);
        var coverage = coverage(runId, question,
                new SupportedSurface("ZX-100 必须备案", List.of(evidence.id())));

        var decision = policy.decide(
                new QuestionPlan(runId, "目标", List.of(question)), coverage, List.of(evidence), Set.of());

        assertTrue(decision.isPresent());
        assertEquals("ZX-100 必须备案",
                decision.orElseThrow().sections().getFirst().surfaces().getFirst().statement());
        assertEquals(List.of("缺少备案例外条款"), decision.orElseThrow().gaps());
    }

    @Test
    void rejectsUnknownCrossQuestionAndShallowEvidence() {
        var runId = UUID.randomUUID();
        var first = new SubQuestion(UUID.randomUUID(), "第一问", List.of("原文"), 5);
        var second = new SubQuestion(UUID.randomUUID(), "第二问", List.of("原文"), 5);
        var deep = evidence(first.id(), true);
        var shallow = evidence(second.id(), false);
        var plan = new QuestionPlan(runId, "目标", List.of(first));

        assertTrue(policy.decide(plan, coverage(runId, first,
                        new SupportedSurface("未知事实", List.of(UUID.randomUUID()))),
                List.of(deep), Set.of()).isEmpty());
        assertTrue(policy.decide(plan, coverage(runId, first,
                        new SupportedSurface("跨题事实", List.of(shallow.id()))),
                List.of(deep, shallow), Set.of()).isEmpty());
        assertTrue(policy.decide(new QuestionPlan(runId, "目标", List.of(second)), coverage(runId, second,
                        new SupportedSurface("浅层事实", List.of(shallow.id()))),
                List.of(shallow), Set.of()).isEmpty());
    }

    @Test
    void rejectsJudgeFailuresAndLegacyCoverageWithoutSupportedSurfaces() throws Exception {
        var runId = UUID.randomUUID();
        var question = new SubQuestion(UUID.randomUUID(), "问题", List.of("原文"), 5);
        var evidence = evidence(question.id(), true);
        var plan = new QuestionPlan(runId, "目标", List.of(question));
        var legacy = new ObjectMapper().readValue("""
                {"runId":"%s","items":[{
                  "subQuestionId":"%s","covered":false,"deepReadEvidenceFamilies":1,
                  "gaps":["缺口"],"hasConflict":false
                }]}
                """.formatted(runId, question.id()), CoverageReport.class);

        assertEquals(List.of(), legacy.items().getFirst().supportedSurfaces());
        assertTrue(policy.decide(plan, legacy, List.of(evidence), Set.of()).isEmpty());
        assertTrue(policy.decide(plan, coverage(runId, question,
                        new SupportedSurface("已有事实", List.of(evidence.id()))),
                List.of(evidence), Set.of(1)).isEmpty());
    }

    private CoverageReport coverage(UUID runId, SubQuestion question, SupportedSurface surface) {
        return new CoverageReport(runId, List.of(new SubQuestionCoverage(
                question.id(), false, 1, List.of("缺少备案例外条款"), false, List.of(surface))));
    }

    private EvidenceItem evidence(UUID questionId, boolean deepRead) {
        return new EvidenceItem(UUID.randomUUID(), questionId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "原文证据", 0, 4, 0.9, deepRead, List.of("parent-expand"));
    }
}
