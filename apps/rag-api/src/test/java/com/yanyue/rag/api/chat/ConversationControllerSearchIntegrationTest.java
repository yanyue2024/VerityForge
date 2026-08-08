package com.yanyue.rag.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.pipeline.AssistantProfileService;
import com.yanyue.rag.contract.chat.UpdateConversationRequest;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

class ConversationControllerSearchIntegrationTest {
    private static PostgreSQLContainer postgres;
    private static DSLContext dsl;

    private UUID organizationId;
    private UUID userId;
    private AuthenticatedUser user;
    private ConversationController controller;

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
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        userId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)",
                organizationId, "search-" + organizationId);
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, 'test', 'Search User', 'ADMIN')
                """, userId, organizationId, "search-" + userId);
        user = new AuthenticatedUser(userId, organizationId, "search-user", "ADMIN", 0);
        controller = new ConversationController(
                dsl, new ObjectMapper().findAndRegisterModules(), mock(AssistantProfileService.class));
    }

    @Test
    void searchesOwnedConversationTitlesAndUserQuestionsButNotAssistantAnswers() {
        var titleMatch = conversation(userId, "Kubernetes API 安全配置", "USER", false);
        var questionMatch = conversation(userId, "生产环境排查", "USER", false);
        message(questionMatch, "user", "如何限制 Kubernetes 控制面访问？");

        var assistantOnly = conversation(userId, "常规问答", "USER", false);
        message(assistantOnly, "assistant", "Kubernetes API 应使用最小权限。");

        var otherUserId = appUser(organizationId);
        var otherUser = conversation(otherUserId, "Kubernetes 私有会话", "USER", false);
        message(otherUser, "user", "Kubernetes 机密内容");

        var deleted = conversation(userId, "Kubernetes 已删除", "USER", true);
        var evaluation = conversation(userId, "Kubernetes 评测", "EVALUATION", false);

        var result = controller.list(user, null, 30, "Kubernetes");
        var ids = result.items().stream().map(item -> item.id()).collect(Collectors.toSet());

        assertEquals(Set.of(titleMatch, questionMatch), ids);
        assertEquals(2, result.items().size());
        assertFalse(ids.contains(assistantOnly));
        assertFalse(ids.contains(otherUser));
        assertFalse(ids.contains(deleted));
        assertFalse(ids.contains(evaluation));
    }

    @Test
    void pinsAndUnpinsConversationAndMovesPinnedConversationToTheTop() {
        var older = conversation(userId, "较早的会话", "USER", false);
        var pinnedId = conversation(userId, "需要置顶的会话", "USER", false);

        var pinned = controller.update(
                user, pinnedId, new UpdateConversationRequest(null, true, null));

        assertTrue(pinned.pinned());
        assertNotNull(pinned.pinnedAt());
        assertEquals(pinnedId, controller.list(user, null, 30, null).items().getFirst().id());

        var unpinned = controller.update(
                user, pinnedId, new UpdateConversationRequest(null, false, null));

        assertFalse(unpinned.pinned());
        assertNull(unpinned.pinnedAt());
        var items = controller.list(user, null, 30, null).items();
        assertEquals(Set.of(older, pinnedId),
                items.stream().map(item -> item.id()).collect(Collectors.toSet()));
        assertTrue(items.stream().noneMatch(item -> item.pinned()));
    }

    @Test
    void reportsPreGenerationLatencyForConversationMessages() {
        var conversationId = conversation(userId, "处理耗时", "USER", false);
        var runId = completedRun(conversationId, 12);
        dsl.execute("""
                INSERT INTO rag_run_event
                    (event_id, run_id, sequence, event_type, payload, created_at)
                VALUES (?, ?, 1, 'ANSWER_MODE_SELECTED', '{}'::jsonb, ?::timestamptz),
                       (?, ?, 2, 'ANSWER_GENERATION_STARTED', '{}'::jsonb, ?::timestamptz)
                """, UUID.randomUUID(), runId, OffsetDateTime.parse("2026-08-06T08:00:04Z"),
                UUID.randomUUID(), runId, OffsetDateTime.parse("2026-08-06T08:00:05Z"));
        assistantMessage(conversationId, runId);

        var result = controller.messages(user, conversationId);

        assertEquals(4_000L, result.getFirst().latencyMs());
    }

    @Test
    void fallsBackToTotalLatencyForLegacyRunsWithoutGenerationEvents() {
        var conversationId = conversation(userId, "旧版处理耗时", "USER", false);
        var runId = completedRun(conversationId, 12);
        assistantMessage(conversationId, runId);

        var result = controller.messages(user, conversationId);

        assertEquals(12_000L, result.getFirst().latencyMs());
    }

    private UUID appUser(UUID organization) {
        var id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, 'test', 'Other User', 'ADMIN')
                """, id, organization, "other-" + id);
        return id;
    }

    private UUID conversation(UUID ownerId, String title, String kind, boolean deleted) {
        var id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO conversation
                    (id, organization_id, title, created_by, conversation_kind, deleted_at)
                VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() ELSE NULL END)
                """, id, organizationId, title, ownerId, kind, deleted);
        return id;
    }

    private UUID completedRun(UUID conversationId, int durationSeconds) {
        var runId = UUID.randomUUID();
        var startedAt = OffsetDateTime.parse("2026-08-06T08:00:00Z");
        dsl.execute("""
                INSERT INTO rag_run
                    (id, conversation_id, organization_id, requested_mode, selected_mode, query_text,
                     scope, filters, status, pipeline_version, prompt_version,
                     started_at, completed_at, created_by)
                VALUES (?, ?, ?, 'FAST', 'FAST', 'test question',
                        '{}'::jsonb, '[]'::jsonb, 'COMPLETED', 'test-pipeline', 'test-prompt',
                        ?::timestamptz, ?::timestamptz, ?)
                """, runId, conversationId, organizationId,
                startedAt, startedAt.plusSeconds(durationSeconds), userId);
        return runId;
    }

    private void assistantMessage(UUID conversationId, UUID runId) {
        dsl.execute("""
                INSERT INTO conversation_message (conversation_id, role, content, run_id)
                VALUES (?, 'assistant', 'test answer', ?)
                """, conversationId, runId);
    }

    private void message(UUID conversationId, String role, String content) {
        dsl.execute("""
                INSERT INTO conversation_message (conversation_id, role, content)
                VALUES (?, ?, ?)
                """, conversationId, role, content);
    }
}
