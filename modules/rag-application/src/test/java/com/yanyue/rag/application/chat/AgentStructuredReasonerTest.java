package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.EvidenceItem;
import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.FactItem;
import com.yanyue.rag.domain.agent.FactStatus;
import com.yanyue.rag.domain.agent.QuestionPlan;
import com.yanyue.rag.domain.agent.SearchMode;
import com.yanyue.rag.domain.agent.SubQuestion;
import com.yanyue.rag.domain.agent.SubQuestionCoverage;
import com.yanyue.rag.domain.port.StructuredReasoningModelPort;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentStructuredReasonerTest {
    @Test
    void validatesPlanDependenciesAndSearchStrategies() {
        var model = new StubModel("""
                {"subQuestions":[
                  {"key":"q1","question":"第一问","priority":5,"dependencies":[],
                   "expectedEvidence":["制度"],"searchMode":"KEYWORD","completionCondition":"找到制度原文"},
                  {"key":"q2","question":"第二问","priority":3,"dependencies":["q1"],
                   "expectedEvidence":["规则"],"searchMode":"HYBRID","completionCondition":"完成对比"}
                ]}
                """);
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());

        var plan = reasoner.plan(UUID.randomUUID(), UUID.randomUUID(), "目标", 6);

        assertEquals(2, plan.subQuestions().size());
        assertEquals(SearchMode.KEYWORD, plan.subQuestions().getFirst().searchMode());
        assertEquals(List.of(plan.subQuestions().getFirst().id()), plan.subQuestions().getLast().dependencies());
    }

    @Test
    void repairsInvalidStructuredOutputOnce() {
        var model = new StubModel("not-json", """
                {"mode":"DEEP","reason":"需要综合多个来源"}
                """);
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());

        var decision = reasoner.classify(UUID.randomUUID(), "复杂问题");

        assertEquals("DEEP", decision.mode().name());
        assertEquals(2, model.calls);
    }

    @Test
    void leavesPlannerTransportRetriesToTheModelAdapter() {
        var calls = new AtomicInteger();
        var model = new StructuredReasoningModelPort() {
            @Override
            public String completeJson(UUID profileId, String operation, String systemPrompt, String userPrompt) {
                calls.incrementAndGet();
                throw new IllegalStateException("upstream timeout");
            }
        };
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());

        var failure = assertThrows(IllegalStateException.class,
                () -> reasoner.plan(UUID.randomUUID(), UUID.randomUUID(), "查找 ZX-100", 6));

        assertEquals(1, calls.get());
        assertEquals("upstream timeout", failure.getMessage());
    }

    @Test
    void doesNotTurnEvidenceTransportFailureIntoSemanticRepair() {
        var calls = new AtomicInteger();
        var model = new StructuredReasoningModelPort() {
            @Override
            public String completeJson(UUID profileId, String operation, String systemPrompt, String userPrompt) {
                calls.incrementAndGet();
                throw new IllegalStateException("upstream timeout");
            }
        };
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());

        var failure = assertThrows(IllegalStateException.class,
                () -> reasoner.extractEvidenceSpans(UUID.randomUUID(), "ZX-100 要求", List.of(
                        new AgentStructuredReasoner.EvidenceContext("q1:c1", "制度规定：ZX-100 必须备案。"))));

        assertEquals(1, calls.get());
        assertEquals("upstream timeout", failure.getMessage());
    }

    @Test
    void separatesFactExtractionFromEntailmentJudgment() {
        var evidenceId = UUID.randomUUID();
        var model = new StubModel(
                "{\"facts\":[{\"statement\":\"受支持事实\",\"evidenceIds\":[\"" + evidenceId
                        + "\"],\"confidence\":0.9},{\"statement\":\"扩大结论\",\"evidenceIds\":[\""
                        + evidenceId + "\"],\"confidence\":0.7}]}",
                """
                {"judgments":[
                  {"factIndex":0,"supported":true,"reason":"原文直接支持"},
                  {"factIndex":1,"supported":false,"reason":"超出原文范围"}
                ]}
                """
        );
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());
        var question = new SubQuestion(UUID.randomUUID(), "问题", List.of("原文"), 1);
        var evidence = new EvidenceItem(evidenceId, question.id(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "原文证据", 0, 4, 0.9, true, List.of("semantic"));

        var facts = reasoner.extractAndVerifyFacts(UUID.randomUUID(), question, List.of(evidence));

        assertEquals(2, facts.size());
        assertTrue(facts.getFirst().supported());
        assertFalse(facts.getLast().supported());
        assertEquals("超出原文范围", facts.getLast().reason());
    }

    @Test
    void extractsOnlyVerbatimEvidenceAndRepairsAChangedQuote() {
        var model = new StubModel(
                """
                {"items":[{"contextKey":"q1:c1","quote":"审批额度大约为一百万元"}]}
                """,
                """
                {"items":[{"contextKey":"q1:c1","quote":"审批额度为100万元"}]}
                """
        );
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());
        var context = new AgentStructuredReasoner.EvidenceContext(
                "q1:c1", "申请条件如下：审批额度为100万元，超过额度需复核。");

        var spans = reasoner.extractEvidenceSpans(
                UUID.randomUUID(), "审批额度是多少？", List.of(context));

        assertEquals(1, spans.size());
        assertEquals("审批额度为100万元", spans.getFirst().quote());
        assertEquals(context.text().indexOf(spans.getFirst().quote()), spans.getFirst().startOffset());
        assertEquals(spans.getFirst().startOffset() + spans.getFirst().quote().length(),
                spans.getFirst().endOffset());
        assertEquals(2, model.calls);
    }

    @Test
    void preservesOriginalSourceWhenModelOnlyNormalizesWhitespace() {
        var model = new StubModel(
                "{\"items\":[{\"contextKey\":\"q1:c1\",\"quote\":\"依赖组件需要\\n部署完成\"}]}"
        );
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());
        var context = new AgentStructuredReasoner.EvidenceContext(
                "q1:c1", "前置条件：依赖组件需要部署\n完成后才能启动。\n限制：不得跳过校验。");

        var spans = reasoner.extractEvidenceSpans(
                UUID.randomUUID(), "依赖组件如何准备？", List.of(context));

        assertEquals(1, spans.size());
        assertEquals("依赖组件需要部署\n完成", spans.getFirst().quote());
        assertEquals(context.text().indexOf(spans.getFirst().quote()), spans.getFirst().startOffset());
        assertEquals(spans.getFirst().startOffset() + spans.getFirst().quote().length(),
                spans.getFirst().endOffset());
    }

    @Test
    void extractsIndependentSubQuestionsInOneBoundedBatch() throws Exception {
        var model = new StubModel("""
                {"items":[
                  {"contextKey":"first:c1","quote":"编号ZX-100必须备案"},
                  {"contextKey":"second:c1","quote":"高风险申请需要双人复核"}
                ]}
                """);
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());

        var spans = reasoner.extractEvidenceSpansBatch(UUID.randomUUID(), List.of(
                new AgentStructuredReasoner.EvidenceRequest("first", "编号要求是什么？", List.of(
                        new AgentStructuredReasoner.EvidenceContext(
                                "first:c1", "制度规定：编号ZX-100必须备案。"))),
                new AgentStructuredReasoner.EvidenceRequest("second", "复核要求是什么？", List.of(
                        new AgentStructuredReasoner.EvidenceContext(
                                "second:c1", "流程说明：高风险申请需要双人复核。")))
        ));

        assertEquals(1, model.calls);
        assertEquals(2, spans.size());
        var sent = new ObjectMapper().readTree(model.lastUserPrompt);
        assertEquals(2, sent.path("requests").size());
        assertEquals("first", sent.path("requests").path(0).path("subQuestionKey").asText());
        assertEquals("second:c1",
                sent.path("requests").path(1).path("contexts").path(0).path("key").asText());
    }

    @Test
    void computesCoverageEvidenceAndFactMinimumsInsteadOfTrustingModelCounts() {
        var first = new SubQuestion(UUID.randomUUID(), "有证据的问题", List.of("制度"), 5);
        var second = new SubQuestion(UUID.randomUUID(), "没有证据的问题", List.of("制度"), 4);
        var runId = UUID.randomUUID();
        var evidenceId = UUID.randomUUID();
        var evidence = new EvidenceItem(evidenceId, first.id(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "支持原文", 0, 4, 0.9, true, List.of("rerank"));
        var fact = new FactItem(UUID.randomUUID(), first.id(), "支持事实", List.of(evidenceId),
                0.9, FactStatus.ACCEPTED, null);
        var model = new StubModel("""
                {"items":[
                  {"key":"q1","covered":true,"supportedSurfaces":[
                    {"statement":"支持事实","evidenceIds":["%s"]}
                  ],"gaps":[],"hasConflict":false},
                  {"key":"q2","covered":true,"supportedSurfaces":[],"gaps":[],"hasConflict":false}
                ]}
                """.formatted(evidenceId));
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());

        var coverage = reasoner.coverage(UUID.randomUUID(), runId,
                new QuestionPlan(runId, "目标", List.of(first, second)), List.of(evidence), List.of(fact));

        assertTrue(coverage.items().getFirst().covered());
        assertEquals(1, coverage.items().getFirst().deepReadEvidenceFamilies());
        assertFalse(coverage.items().getLast().covered());
        assertEquals(0, coverage.items().getLast().deepReadEvidenceFamilies());
        assertTrue(coverage.items().getLast().gaps().contains("该子问题没有已深读证据族"));
        assertTrue(coverage.items().getLast().gaps().contains("该子问题没有通过蕴含审核的事实"));
    }

    @Test
    void lightweightEvidenceJudgeDoesNotRequireFactLedgerButStillRequiresDeepReadEvidence() {
        var first = new SubQuestion(UUID.randomUUID(), "已深读问题", List.of("制度"), 5);
        var second = new SubQuestion(UUID.randomUUID(), "未深读问题", List.of("制度"), 4);
        var runId = UUID.randomUUID();
        var evidence = new EvidenceItem(UUID.randomUUID(), first.id(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "审批额度为100万元", 20, 31, 0.91, true, List.of("parent-expand"));
        var model = new StubModel("""
                {"items":[
                  {"key":"q1","covered":true,"supportedSurfaces":[
                    {"statement":"审批额度为100万元","evidenceIds":["%s"]}
                  ],"gaps":[],"hasConflict":false},
                  {"key":"q2","covered":true,"supportedSurfaces":[],"gaps":[],"hasConflict":false}
                ]}
                """.formatted(evidence.id()));
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());

        var coverage = reasoner.evidenceCoverage(UUID.randomUUID(), runId,
                new QuestionPlan(runId, "目标", List.of(first, second)), List.of(evidence));

        assertTrue(coverage.items().getFirst().covered());
        assertFalse(coverage.items().getLast().covered());
        assertTrue(coverage.items().getLast().gaps().contains("该子问题没有已深读证据族"));
        assertFalse(coverage.items().getFirst().gaps().contains("该子问题没有通过蕴含审核的事实"));
        assertEquals(List.of(evidence.id()),
                coverage.items().getFirst().supportedSurfaces().getFirst().evidenceIds());
    }

    @Test
    void repairsSupportedSurfaceThatReferencesAnotherSubQuestion() {
        var first = new SubQuestion(UUID.randomUUID(), "第一问", List.of("第一证据"), 5);
        var second = new SubQuestion(UUID.randomUUID(), "第二问", List.of("第二证据"), 5);
        var runId = UUID.randomUUID();
        var firstEvidence = new EvidenceItem(UUID.randomUUID(), first.id(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "第一问原文", 0, 5, 0.9, true, List.of("parent-expand"));
        var secondEvidence = new EvidenceItem(UUID.randomUUID(), second.id(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "第二问原文", 0, 5, 0.9, true, List.of("parent-expand"));
        var model = new StubModel("""
                {"items":[
                  {"key":"q1","covered":false,"supportedSurfaces":[
                    {"statement":"错误跨题事实","evidenceIds":["%s"]}
                  ],"gaps":["缺少第一问完整条件"],"hasConflict":false},
                  {"key":"q2","covered":false,"supportedSurfaces":[],
                   "gaps":["缺少第二问完整条件"],"hasConflict":false}
                ]}
                """.formatted(secondEvidence.id()), """
                {"items":[
                  {"key":"q1","covered":false,"supportedSurfaces":[
                    {"statement":"第一问已有事实","evidenceIds":["%s"]}
                  ],"gaps":["缺少第一问完整条件"],"hasConflict":false},
                  {"key":"q2","covered":false,"supportedSurfaces":[
                    {"statement":"第二问已有事实","evidenceIds":["%s"]}
                  ],"gaps":["缺少第二问完整条件"],"hasConflict":false}
                ]}
                """.formatted(firstEvidence.id(), secondEvidence.id()));
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());

        var coverage = reasoner.evidenceCoverage(UUID.randomUUID(), runId,
                new QuestionPlan(runId, "目标", List.of(first, second)),
                List.of(firstEvidence, secondEvidence));

        assertEquals(2, model.calls);
        assertEquals("第一问已有事实", coverage.items().getFirst().supportedSurfaces().getFirst().statement());
        assertEquals(List.of(firstEvidence.id()),
                coverage.items().getFirst().supportedSurfaces().getFirst().evidenceIds());
    }

    @Test
    void normalizesGapKeysAndIgnoresUnmappableQueries() {
        var first = new SubQuestion(UUID.randomUUID(), "第一问", List.of("证据一"), 5);
        var second = new SubQuestion(UUID.randomUUID(), "第二问", List.of("证据二"), 4);
        var runId = UUID.randomUUID();
        var model = new StubModel("""
                {"queries":[
                  {"key":" Q1 ","query":"第一问补充检索","searchMode":"KEYWORD"},
                  {"key":"%s","query":"第二问补充检索","searchMode":"SEMANTIC"},
                  {"key":"not-in-plan","query":"必须忽略","searchMode":"HYBRID"}
                ]}
                """.formatted(second.id()));
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());
        var plan = new QuestionPlan(runId, "目标", List.of(first, second));
        var coverage = new CoverageReport(runId, List.of(
                new SubQuestionCoverage(first.id(), false, 1, List.of("缺口一"), false),
                new SubQuestionCoverage(second.id(), false, 1, List.of("缺口二"), false)));

        var queries = reasoner.gapQueries(UUID.randomUUID(), plan, coverage, List.of("已有查询"));

        assertEquals(2, queries.size());
        assertEquals(first.id(), queries.getFirst().subQuestionId());
        assertEquals(SearchMode.KEYWORD, queries.getFirst().searchMode());
        assertEquals(second.id(), queries.getLast().subQuestionId());
        assertEquals(SearchMode.SEMANTIC, queries.getLast().searchMode());
    }

    @Test
    void gapQueriesIgnoreCoveredQuestionsAndPreviouslyUsedQueries() {
        var covered = new SubQuestion(UUID.randomUUID(), "已覆盖", List.of("证据"), 5);
        var missing = new SubQuestion(UUID.randomUUID(), "缺口问题", List.of("证据"), 4);
        var runId = UUID.randomUUID();
        var model = new StubModel("""
                {"queries":[
                  {"key":"q1","query":"不应再次检索","searchMode":"HYBRID"},
                  {"key":"q2","query":"  已有 查询  ","searchMode":"KEYWORD"},
                  {"key":"q2","query":"缺失条款编号","searchMode":"KEYWORD"}
                ]}
                """);
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());
        var plan = new QuestionPlan(runId, "目标", List.of(covered, missing));
        var coverage = new CoverageReport(runId, List.of(
                new SubQuestionCoverage(covered.id(), true, 1, List.of(), false),
                new SubQuestionCoverage(missing.id(), false, 1, List.of("缺少条款编号"), false)));

        var queries = reasoner.gapQueries(UUID.randomUUID(), plan, coverage, List.of("已有 查询"));

        assertEquals(1, queries.size());
        assertEquals(missing.id(), queries.getFirst().subQuestionId());
        assertEquals("缺失条款编号", queries.getFirst().query());
        assertEquals(SearchMode.KEYWORD, queries.getFirst().searchMode());
    }

    @Test
    void limitsGapGenerationToOneQueryPerUncoveredQuestionInEachRound() {
        var missing = new SubQuestion(UUID.randomUUID(), "缺口问题", List.of("制度原文"), 5);
        var runId = UUID.randomUUID();
        var model = new StubModel("""
                {"queries":[
                  {"key":"q1","query":"缺口问题 条款编号","searchMode":"KEYWORD"},
                  {"key":"q1","query":"缺口问题 适用范围","searchMode":"SEMANTIC"},
                  {"key":"q1","query":"缺口问题 完整原文","searchMode":"HYBRID"}
                ]}
                """);
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());
        var plan = new QuestionPlan(runId, "目标", List.of(missing));
        var coverage = new CoverageReport(runId, List.of(
                new SubQuestionCoverage(missing.id(), false, 0, List.of("缺少制度原文"), false)));

        var queries = reasoner.gapQueries(UUID.randomUUID(), plan, coverage, List.of());

        assertEquals(1, queries.size());
        assertEquals("缺口问题 条款编号", queries.getFirst().query());
        assertEquals(SearchMode.KEYWORD, queries.getFirst().searchMode());
    }

    @Test
    void repairsCrossQuestionGapPollutionAndBackfillsMissingQuestions() {
        var deployment = new SubQuestion(
                UUID.randomUUID(), "adoctor-check 5.2 依赖组件部署", List.of("部署原文"), 5);
        var virtualMachine = new SubQuestion(
                UUID.randomUUID(), "日常治理虚拟机 核心定位", List.of("虚拟机原文"), 5);
        var runId = UUID.randomUUID();
        var model = new StubModel("""
                {"queries":[
                  {"key":"q2","query":"adoctor-check 依赖组件部署 前置条件 fluentd", "searchMode":"KEYWORD"}
                ]}
                """);
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());
        var plan = new QuestionPlan(runId, "两个独立目标", List.of(deployment, virtualMachine));
        var coverage = new CoverageReport(runId, List.of(
                new SubQuestionCoverage(deployment.id(), false, 1,
                        List.of("缺少部署限制"), false),
                new SubQuestionCoverage(virtualMachine.id(), false, 1,
                        List.of("缺少虚拟机生命周期定位原文"), false)));

        var queries = reasoner.gapQueries(UUID.randomUUID(), plan, coverage, List.of());

        assertEquals(2, queries.size());
        var vmQuery = queries.stream()
                .filter(query -> query.subQuestionId().equals(virtualMachine.id())).findFirst().orElseThrow();
        assertTrue(vmQuery.query().startsWith(virtualMachine.question()));
        assertFalse(vmQuery.query().contains("adoctor-check"));
        assertEquals(SearchMode.HYBRID, vmQuery.searchMode());
        var deploymentQuery = queries.stream()
                .filter(query -> query.subQuestionId().equals(deployment.id())).findFirst().orElseThrow();
        assertTrue(deploymentQuery.query().startsWith(deployment.question()));
        assertEquals(SearchMode.HYBRID, deploymentQuery.searchMode());
    }

    @Test
    void repairsCrossQuestionPollutionInShortChineseQueries() {
        var revenue = new SubQuestion(
                UUID.randomUUID(), "某公司2024年营收是多少", List.of("营收原文"), 5);
        var profit = new SubQuestion(
                UUID.randomUUID(), "某公司2024年利润是多少", List.of("利润原文"), 5);
        var runId = UUID.randomUUID();
        var model = new StubModel("""
                {"queries":[
                  {"key":"q2","query":"某公司2024年营收", "searchMode":"KEYWORD"}
                ]}
                """);
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());
        var plan = new QuestionPlan(runId, "对比营收与利润", List.of(revenue, profit));
        var coverage = new CoverageReport(runId, List.of(
                new SubQuestionCoverage(revenue.id(), true, 1, List.of(), false),
                new SubQuestionCoverage(profit.id(), false, 0, List.of("缺少利润原文"), false)));

        var queries = reasoner.gapQueries(UUID.randomUUID(), plan, coverage, List.of());

        assertEquals(1, queries.size());
        assertEquals(profit.id(), queries.getFirst().subQuestionId());
        assertTrue(queries.getFirst().query().contains("利润"));
        assertFalse(queries.getFirst().query().contains("营收"));
        assertEquals(SearchMode.HYBRID, queries.getFirst().searchMode());
    }

    @Test
    void keepsAChineseGapQueryAnchoredToItsAssignedQuestion() {
        var revenue = new SubQuestion(
                UUID.randomUUID(), "某公司2024年营收是多少", List.of("营收原文"), 5);
        var profit = new SubQuestion(
                UUID.randomUUID(), "某公司2024年利润是多少", List.of("利润原文"), 5);
        var runId = UUID.randomUUID();
        var model = new StubModel("""
                {"queries":[
                  {"key":"q2","query":"某公司2024年利润", "searchMode":"SEMANTIC"}
                ]}
                """);
        var reasoner = new AgentStructuredReasoner(model, new ObjectMapper());
        var plan = new QuestionPlan(runId, "对比营收与利润", List.of(revenue, profit));
        var coverage = new CoverageReport(runId, List.of(
                new SubQuestionCoverage(revenue.id(), true, 1, List.of(), false),
                new SubQuestionCoverage(profit.id(), false, 0, List.of("缺少利润原文"), false)));

        var queries = reasoner.gapQueries(UUID.randomUUID(), plan, coverage, List.of());

        assertEquals(1, queries.size());
        assertEquals("某公司2024年利润", queries.getFirst().query());
        assertEquals(SearchMode.SEMANTIC, queries.getFirst().searchMode());
    }

    private static final class StubModel implements StructuredReasoningModelPort {
        private final ArrayDeque<String> responses;
        private int calls;
        private String lastUserPrompt;

        private StubModel(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public String completeJson(UUID profileId, String operation, String systemPrompt, String userPrompt) {
            calls++;
            lastUserPrompt = userPrompt;
            return responses.removeFirst();
        }
    }
}
