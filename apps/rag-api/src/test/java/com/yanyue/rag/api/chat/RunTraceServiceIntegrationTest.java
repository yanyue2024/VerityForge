package com.yanyue.rag.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RunTraceServiceIntegrationTest {
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-08-03T08:00:00Z");
    private static PostgreSQLContainer postgres;
    private static DSLContext dsl;
    private static ObjectMapper objectMapper;

    private UUID organizationId;
    private RunTraceService service;
    private long sequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)",
                organizationId, "trace-" + organizationId);
        service = new RunTraceService(dsl, objectMapper);
        sequence = 0;
    }

    @Test
    void projectsFastTraceWithoutExposingInternalIdentifiers() {
        var runId = run("FAST", "FAST", "COMPLETED", "GROUNDED", "SUFFICIENT", 3, 8);
        event(runId, 0, "RUN_ACCEPTED", Map.of());
        event(runId, 1, "ROUTE_SELECTED", Map.of("selected", "FAST"));
        event(runId, 2, "QUERY_REWRITE_STARTED", Map.of("hasConversationContext", true));
        event(runId, 3, "QUERY_REWRITTEN", Map.of(
                "original", "它有什么限制", "rewritten", "Kubernetes API 有哪些访问限制"));
        event(runId, 4, "RETRIEVAL_STARTED", Map.of());
        event(runId, 5, "RETRIEVAL_RESULT", Map.of(
                "keywordCount", 12, "semanticCount", 15, "candidateCount", 20));
        event(runId, 6, "RERANK_COMPLETED", Map.of("candidateCount", 6));
        event(runId, 7, "ANSWER_GENERATION_STARTED", Map.of("answerMode", "GROUNDED"));
        event(runId, 8, "ANSWER_DELTA", Map.of("text", "回答"));

        var trace = service.trace(runId);

        assertEquals("FAST", trace.path());
        assertEquals("COMPLETED", trace.state());
        assertTrue(trace.traceAvailable());
        assertEquals(List.of("问题改写", "混合检索", "相关性排序", "生成回答"), labels(trace));
        assertEquals("20 个合并候选", trace.nodes().get(1).summary());
        assertEquals("6 个候选完成相关性排序", trace.nodes().get(2).summary());
        assertEquals(STARTED_AT.plusSeconds(8).toInstant(), trace.firstAnswerAt());
        var safeDetails = trace.nodes().stream()
                .flatMap(node -> node.details().stream())
                .map(detail -> detail.label() + "=" + detail.value())
                .toList();
        assertTrue(safeDetails.stream().noneMatch(value -> value.contains(runId.toString())));
        assertTrue(safeDetails.stream().noneMatch(value -> value.toLowerCase().contains("model")));
    }

    @Test
    void projectsDeepTraceWithGoalProgressAndConditionalRepair() {
        var runId = run("AUTO", "DEEP", "COMPLETED", "PARTIAL_GROUNDED", "PARTIAL", 4, 12);
        var firstGoal = UUID.randomUUID().toString();
        var secondGoal = UUID.randomUUID().toString();
        event(runId, 0, "RUN_ACCEPTED", Map.of());
        event(runId, 1, "ROUTE_SELECTED", Map.of("selected", "DEEP"));
        event(runId, 2, "PLAN_CREATED", Map.of(
                "standaloneObjective", "梳理 Kubernetes API 的安全使用要求",
                "goals", List.of(
                        Map.of("id", firstGoal, "question", "认证与授权要求"),
                        Map.of("id", secondGoal, "question", "TLS 与网络边界"))));
        event(runId, 3, "GOAL_RESEARCH_STARTED", Map.of(
                "phase", "PRIMARY", "goalId", firstGoal, "queryCount", 2));
        event(runId, 3, "GOAL_RESEARCH_STARTED", Map.of(
                "phase", "PRIMARY", "goalId", secondGoal, "queryCount", 2));
        event(runId, 5, "GOAL_RESEARCH_COMPLETED", Map.of(
                "phase", "PRIMARY", "goalId", firstGoal, "acceptedEvidenceCount", 3));
        event(runId, 5, "GOAL_RESEARCH_COMPLETED", Map.of(
                "phase", "PRIMARY", "goalId", secondGoal, "acceptedEvidenceCount", 0));
        event(runId, 6, "EVIDENCE_JUDGE_STARTED", Map.of());
        event(runId, 7, "EVIDENCE_JUDGE_COMPLETED", Map.of("degraded", false));
        event(runId, 7, "GAP_IDENTIFIED", Map.of("goalId", secondGoal));
        event(runId, 8, "GOAL_RESEARCH_STARTED", Map.of(
                "phase", "REPAIR", "goalId", secondGoal, "queryCount", 2));
        event(runId, 9, "GOAL_RESEARCH_COMPLETED", Map.of(
                "phase", "REPAIR", "goalId", secondGoal, "acceptedEvidenceCount", 1));
        event(runId, 10, "ANSWER_GENERATION_STARTED", Map.of("answerMode", "PARTIAL_GROUNDED"));
        event(runId, 11, "ANSWER_DELTA", Map.of("text", "回答"));

        var trace = service.trace(runId);

        assertEquals("DEEP", trace.path());
        assertEquals(List.of("制定计划", "并行检索", "收集证据", "验证证据", "补充检索", "生成回答"),
                labels(trace));
        assertEquals("2/2 个目标完成", trace.nodes().get(1).summary());
        assertEquals(2, trace.nodes().get(1).goals().size());
        assertEquals("1 个证据缺口", trace.nodes().get(3).summary());
        assertEquals("1 条新增证据", trace.nodes().get(4).summary());
        assertEquals("已结合部分内部证据完成回答", trace.nodes().getLast().summary());
    }

    @Test
    void projectsConversationalPathWithoutKnowledgeStages() {
        var runId = run("AUTO", "DEEP", "COMPLETED", "CONVERSATIONAL", "EMPTY", 0, 4);
        event(runId, 0, "RUN_ACCEPTED", Map.of());
        event(runId, 1, "ROUTE_SELECTED", Map.of("selected", "DEEP"));
        event(runId, 2, "ANSWER_MODE_SELECTED", Map.of(
                "mode", "CONVERSATIONAL", "retrievalHealth", "EMPTY", "evidenceCount", 0));
        event(runId, 3, "ANSWER_GENERATION_STARTED", Map.of("answerMode", "CONVERSATIONAL"));
        event(runId, 4, "ANSWER_DELTA", Map.of("text", "你好"));

        var trace = service.trace(runId);

        assertEquals("CONVERSATIONAL", trace.path());
        assertEquals(List.of("理解问题", "生成回答"), labels(trace));
        assertEquals("已识别为普通对话", trace.nodes().getFirst().summary());
        assertEquals("已完成回答", trace.nodes().getLast().summary());
        assertEquals(0, trace.evidenceCount());
    }

    @Test
    void keepsLegacyTerminalRunStaticWhenItHasNoMeaningfulTraceEvents() {
        var runId = run("DEEP", "DEEP", "COMPLETED", null, null, 0, 2);
        event(runId, 0, "RUN_ACCEPTED", Map.of());
        event(runId, 2, "RUN_COMPLETED", Map.of());

        var trace = service.trace(runId);

        assertFalse(trace.traceAvailable());
        assertTrue(trace.nodes().isEmpty());
        assertEquals("COMPLETED", trace.state());
        assertNull(trace.firstAnswerAt());
        assertEquals(2_000L, trace.durationMs());
    }

    private UUID run(
            String requestedMode,
            String selectedMode,
            String status,
            String answerMode,
            String retrievalHealth,
            int evidenceCount,
            int durationSeconds
    ) {
        var runId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO rag_run
                    (id, organization_id, requested_mode, selected_mode, query_text,
                     scope, filters, status, pipeline_version, prompt_version,
                     answer_mode, retrieval_health, evidence_count, started_at, completed_at)
                VALUES (?, ?, ?, ?, 'test question',
                        '{"knowledgeBaseIds":["00000000-0000-0000-0000-000000000001"]}'::jsonb,
                        '[{"field":"uploadedAt","operator":"GTE","value":"2026-03-01"}]'::jsonb,
                        ?, 'test-pipeline', 'test-prompt', ?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, runId, organizationId, requestedMode, selectedMode, status,
                answerMode, retrievalHealth, evidenceCount,
                STARTED_AT, STARTED_AT.plusSeconds(durationSeconds));
        return runId;
    }

    private void event(UUID runId, int secondsAfterStart, String type, Map<String, ?> payload) {
        try {
            dsl.execute("""
                    INSERT INTO rag_run_event
                        (event_id, run_id, sequence, event_type, payload, created_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::timestamptz)
                    """, UUID.randomUUID(), runId, ++sequence, type,
                    objectMapper.writeValueAsString(payload), STARTED_AT.plusSeconds(secondsAfterStart));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private List<String> labels(com.yanyue.rag.contract.chat.RunTraceView trace) {
        return trace.nodes().stream().map(node -> node.label()).toList();
    }
}
