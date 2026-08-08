package com.yanyue.rag.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.agent.react.KnowledgeReferenceSource;
import com.yanyue.rag.domain.agent.react.ReactCheckpoint;
import com.yanyue.rag.domain.agent.react.ReactKnowledgeReference;
import com.yanyue.rag.domain.agent.react.ReactStep;
import com.yanyue.rag.domain.agent.react.ReactStepStatus;
import com.yanyue.rag.domain.agent.react.ReactToolCall;
import com.yanyue.rag.domain.agent.react.ReactToolCallStatus;
import com.yanyue.rag.domain.evaluation.EvaluationCaseAttempt;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class JooqAgentReactPersistenceAdapterTest {
    private static PostgreSQLContainer postgres;
    private static DSLContext dsl;
    private static ObjectMapper objectMapper;
    private static UUID legacyActiveRunId;
    private static UUID legacyCompletedRunId;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("20")
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        var organizationId = UUID.randomUUID();
        legacyActiveRunId = UUID.randomUUID();
        legacyCompletedRunId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, 'legacy-upgrade')", organizationId);
        dsl.execute("""
                INSERT INTO rag_run
                    (id, organization_id, requested_mode, selected_mode, query_text, status,
                     pipeline_version, prompt_version, started_at, completed_at)
                VALUES (?, ?, 'DEEP', 'DEEP', 'active legacy', 'RUNNING',
                        'rag-pipeline-v1', 'answer-v1', now(), NULL),
                       (?, ?, 'DEEP', 'DEEP', 'completed legacy', 'COMPLETED',
                        'rag-pipeline-v1', 'answer-v1', now() - interval '2 seconds', now())
                """, legacyActiveRunId, organizationId, legacyCompletedRunId, organizationId);
        dsl.execute("""
                INSERT INTO agent_run_checkpoint (run_id, stage, state)
                VALUES (?, 'RETRIEVE', '{"stage":"RETRIEVE"}'::jsonb)
                """, legacyActiveRunId);
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void migrationInterruptsOnlyActiveLegacyDeepRunsAndKeepsV1CheckpointsReadable() {
        assertEquals("FAILED", dsl.fetchValue(
                "SELECT status FROM rag_run WHERE id = ?", legacyActiveRunId, String.class));
        var errorMessage = dsl.fetchOne(
                "SELECT error_message FROM rag_run WHERE id = ?", legacyActiveRunId)
                .get("error_message", String.class);
        assertTrue(errorMessage.contains("retry required"));
        assertEquals("COMPLETED", dsl.fetchValue(
                "SELECT status FROM rag_run WHERE id = ?", legacyCompletedRunId, String.class));
        assertEquals(1, dsl.fetchValue("""
                SELECT checkpoint_version FROM agent_run_checkpoint WHERE run_id = ?
                """, legacyActiveRunId, Integer.class));
        assertTrue(new JooqAgentReactPersistenceAdapter(dsl, objectMapper)
                .loadCheckpoint(legacyActiveRunId).isEmpty());
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void persistsRecoverableReactArtifactsAndRanksDeepReadDocumentsFirst() {
        var fixture = fixture("react-artifacts");
        var firstDocument = document(fixture, "First discovered", "First search evidence");
        var secondDocument = document(fixture, "Deep read later", "Second search evidence");
        var adapter = new JooqAgentReactPersistenceAdapter(dsl, objectMapper);
        var now = Instant.parse("2026-07-19T00:00:00Z");
        var stepId = UUID.randomUUID();
        var searchCallId = UUID.randomUUID();
        var deepReadCallId = UUID.randomUUID();
        var runningCallId = UUID.randomUUID();
        var firstReferenceId = UUID.randomUUID();
        var secondReferenceId = UUID.randomUUID();

        adapter.saveStep(new ReactStep(
                stepId, fixture.runId(), 1, ReactStepStatus.COMPLETED, "Search and inspect", "",
                "tool_calls", Map.of("requestId", "request-1"), Map.of("totalTokens", 42),
                now, now.plusSeconds(2)));
        adapter.saveToolCall(new ReactToolCall(
                searchCallId, fixture.runId(), stepId, "call-search", 0, "knowledge_search",
                Map.of("queries", List.of("policy")), ReactToolCallStatus.SUCCEEDED, "2 results",
                Map.of("count", 2), Map.of(), 2, 120L, now, now.plusMillis(120)));
        adapter.saveToolCall(new ReactToolCall(
                deepReadCallId, fixture.runId(), stepId, "call-read", 1, "list_knowledge_chunks",
                Map.of("knowledge_id", secondDocument.documentId().toString()),
                ReactToolCallStatus.SUCCEEDED, "full chunk", Map.of("count", 1), Map.of(),
                1, 80L, now.plusSeconds(1), now.plusSeconds(1).plusMillis(80)));
        adapter.saveToolCall(new ReactToolCall(
                runningCallId, fixture.runId(), stepId, "call-interrupted", 2, "get_document_info",
                Map.of("knowledge_ids", List.of(firstDocument.documentId().toString())),
                ReactToolCallStatus.RUNNING, null, Map.of(), Map.of(), null, null,
                now.plusSeconds(2), null));

        adapter.saveKnowledgeReference(reference(
                firstReferenceId, fixture.runId(), searchCallId, firstDocument,
                KnowledgeReferenceSource.KNOWLEDGE_SEARCH, false, 0.87));
        adapter.saveKnowledgeReference(reference(
                secondReferenceId, fixture.runId(), searchCallId, secondDocument,
                KnowledgeReferenceSource.KNOWLEDGE_SEARCH, false, 0.78));
        adapter.saveKnowledgeReference(reference(
                UUID.randomUUID(), fixture.runId(), deepReadCallId, secondDocument,
                KnowledgeReferenceSource.LIST_KNOWLEDGE_CHUNKS, true, 0.93));

        var checkpoint = new ReactCheckpoint(
                fixture.runId(), ReactCheckpoint.CURRENT_VERSION, "OBSERVE", 1,
                List.of(Map.of("role", "user", "content", "Find the policy")),
                Map.of("iterationsUsed", 1, "searchCallsUsed", 1, "deepReadCallsUsed", 1),
                Set.of(firstDocument.chunkId(), secondDocument.chunkId()),
                List.of(firstReferenceId, secondReferenceId),
                List.of(Map.of("providerCallId", "call-interrupted")),
                Map.of("contextCompressed", false), now.plusSeconds(3));
        adapter.saveCheckpoint(checkpoint);

        var loaded = adapter.loadArtifacts(fixture.runId()).orElseThrow();
        assertEquals(2, loaded.artifactVersion());
        assertEquals("OBSERVE", loaded.checkpoint().phase());
        assertEquals(1, loaded.steps().size());
        assertEquals(3, loaded.toolCalls().size());
        assertEquals(2, loaded.knowledgeReferences().size());
        assertEquals(2, loaded.rankedDocuments().size());
        assertEquals(secondDocument.documentId(), loaded.rankedDocuments().getFirst().documentId());
        assertTrue(loaded.rankedDocuments().getFirst().deepRead());
        assertEquals(firstDocument.documentId(), loaded.rankedDocuments().getLast().documentId());
        var upgraded = loaded.knowledgeReferences().stream()
                .filter(reference -> reference.documentId().equals(secondDocument.documentId()))
                .findFirst().orElseThrow();
        assertTrue(upgraded.deepRead());
        assertEquals(List.of(KnowledgeReferenceSource.KNOWLEDGE_SEARCH,
                        KnowledgeReferenceSource.LIST_KNOWLEDGE_CHUNKS).stream().sorted().toList(),
                upgraded.sources().stream().sorted().toList());

        var evaluation = new JooqEvaluationRepository(dsl, objectMapper);
        var diagnostics = evaluation.findRagRunRetrievalDiagnostics(
                fixture.organizationId(), fixture.runId());
        assertEquals(2, ((Number) ((Map<?, ?>) diagnostics.get("tool.knowledge_search"))
                .get("resultCount")).intValue());
        assertEquals(2, ((Number) diagnostics.get("documentCount")).intValue());
        assertEquals(1, ((Number) diagnostics.get("iterationCount")).intValue());
        assertEquals(42, ((Number) diagnostics.get("totalTokens")).intValue());
        assertEquals(List.of(secondDocument.documentId()), diagnostics.get("deepReadDocumentIds"));
        assertEquals(secondDocument.documentId(), evaluation.findRagRunCandidates(
                fixture.organizationId(), fixture.runId()).getFirst().documentId());

        var recoverable = adapter.findRecoverableRuns().stream()
                .filter(run -> run.runId().equals(fixture.runId()))
                .findFirst().orElseThrow();
        assertEquals("react-artifacts question", recoverable.request().query());
        assertEquals(fixture.conversationId(), recoverable.conversationId());

        adapter.prepareForRecovery(fixture.runId());
        var recoveredCall = adapter.loadArtifacts(fixture.runId()).orElseThrow().toolCalls().stream()
                .filter(call -> call.id().equals(runningCallId)).findFirst().orElseThrow();
        assertEquals(ReactToolCallStatus.PENDING, recoveredCall.status());
        assertEquals(null, recoveredCall.startedAt());
    }

    @Test
    void cancelledRunCannotBeCompletedOrFailedByLateCallbacksAndMessagesAreIdempotent() {
        var fixture = fixture("terminal-guard");
        var records = new JooqRunRecordAdapter(dsl, objectMapper);
        var memory = new JooqConversationMemory(dsl, mock(StringRedisTemplate.class));

        records.cancel(fixture.runId());
        records.complete(fixture.runId());
        records.fail(fixture.runId(), "late failure");
        records.markNoAnswer(fixture.runId(), "late no-answer");

        assertEquals("CANCELLED", dsl.fetchValue(
                "SELECT status FROM rag_run WHERE id = ?", fixture.runId(), String.class));
        assertEquals(null, dsl.fetchValue(
                "SELECT error_message FROM rag_run WHERE id = ?", fixture.runId(), String.class));
        assertEquals(null, dsl.fetchValue(
                "SELECT no_answer_reason FROM rag_run WHERE id = ?", fixture.runId(), String.class));

        memory.append(fixture.conversationId(), "user", "original", fixture.runId());
        memory.append(fixture.conversationId(), "user", "retry", fixture.runId());
        memory.append(fixture.conversationId(), "assistant", "draft", fixture.runId());
        memory.append(fixture.conversationId(), "assistant", "final", fixture.runId());

        assertEquals(2, dsl.fetchCount(DSL.table("conversation_message"),
                DSL.field("run_id").eq(fixture.runId())));
        assertEquals("retry", dsl.fetchValue("""
                SELECT content FROM conversation_message WHERE run_id = ? AND role = 'user'
                """, fixture.runId(), String.class));
        assertEquals("final", dsl.fetchValue("""
                SELECT content FROM conversation_message WHERE run_id = ? AND role = 'assistant'
                """, fixture.runId(), String.class));
    }

    @Test
    void reprocessKeepsOneTurnAndAtomicallyReplacesItsActiveRun() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var sourceRunId = UUID.randomUUID();
        var reprocessedRunId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, 'reprocess')", organizationId);
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, 'test', 'Admin', 'ADMIN')
                """, userId, organizationId, "reprocess-" + userId);
        dsl.execute("""
                INSERT INTO conversation (id, organization_id, title, created_by)
                VALUES (?, ?, 'Reprocess conversation', ?)
                """, conversationId, organizationId, userId);
        var request = new CreateRunRequest(
                "Kubernetes API 使用注意事项",
                RunMode.DEEP,
                KnowledgeScope.all(),
                List.of(),
                null);
        var records = new JooqRunRecordAdapter(dsl, objectMapper);
        var memory = new JooqConversationMemory(dsl, mock(StringRedisTemplate.class));

        records.create(sourceRunId, organizationId, userId, conversationId, request);
        records.markRunning(sourceRunId, RunMode.DEEP);
        memory.append(conversationId, "assistant", "First answer", sourceRunId);
        records.complete(sourceRunId);

        var seed = records.prepareReprocess(
                sourceRunId, reprocessedRunId, organizationId, userId);

        assertEquals(conversationId, seed.conversationId());
        assertEquals(request.query(), seed.request().query());
        assertEquals(RunMode.DEEP, seed.request().mode());
        var turnId = dsl.fetchValue(
                "SELECT turn_id FROM rag_run WHERE id = ?", sourceRunId, UUID.class);
        assertEquals(turnId, dsl.fetchValue(
                "SELECT turn_id FROM rag_run WHERE id = ?", reprocessedRunId, UUID.class));
        assertEquals(sourceRunId, dsl.fetchValue("""
                SELECT reprocessed_from_run_id FROM rag_run WHERE id = ?
                """, reprocessedRunId, UUID.class));
        assertEquals(reprocessedRunId, dsl.fetchValue("""
                SELECT active_run_id FROM conversation_turn WHERE id = ?
                """, turnId, UUID.class));
        assertEquals(1L, dsl.fetchValue("""
                SELECT count(*) FROM conversation_message WHERE turn_id = ? AND role = 'user'
                """, turnId, Long.class));
        assertThrows(IllegalArgumentException.class, () -> records.prepareReprocess(
                sourceRunId, UUID.randomUUID(), organizationId, userId));
    }

    @Test
    void persistsEvaluationRequestSnapshotAndAttemptLineageWithoutOverwritingHistory() {
        var organizationId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var caseId = UUID.randomUUID();
        var originalRunId = UUID.randomUUID();
        var resumedRunId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "lineage");
        dsl.execute("INSERT INTO evaluation_dataset (id, organization_id, name) VALUES (?, ?, ?)",
                datasetId, organizationId, "retrieval-200");
        dsl.execute("INSERT INTO evaluation_case (id, dataset_id, question) VALUES (?, ?, ?)",
                caseId, datasetId, "What is retrieved?");
        dsl.execute("""
                INSERT INTO evaluation_run (id, dataset_id, status)
                VALUES (?, ?, 'FAILED'), (?, ?, 'QUEUED')
                """, originalRunId, datasetId, resumedRunId, datasetId);
        var adapter = new JooqEvaluationAttemptAdapter(dsl, objectMapper);

        adapter.saveRequestSnapshot(originalRunId, Map.of("mode", "AGENTIC_RETRIEVAL_ONLY", "concurrency", 2));
        adapter.linkResumedRun(resumedRunId, originalRunId,
                Map.of("mode", "AGENTIC_RETRIEVAL_ONLY", "resume", true));

        var original = adapter.loadLineage(originalRunId).orElseThrow();
        var resumed = adapter.loadLineage(resumedRunId).orElseThrow();
        assertEquals(originalRunId, original.lineageRootId());
        assertEquals(1, original.attemptNumber());
        assertEquals(originalRunId, resumed.lineageRootId());
        assertEquals(originalRunId, resumed.resumedFromRunId());
        assertEquals(2, resumed.attemptNumber());
        assertEquals(true, resumed.requestSnapshot().get("resume"));

        var attemptId = UUID.randomUUID();
        var start = Instant.parse("2026-07-19T01:00:00Z");
        adapter.saveCaseAttempt(new EvaluationCaseAttempt(
                attemptId, resumedRunId, caseId, null, 1, "RUNNING", null,
                Map.of(), null, start, null, start));
        adapter.saveCaseAttempt(new EvaluationCaseAttempt(
                UUID.randomUUID(), resumedRunId, caseId, null, 1, "SUCCEEDED", null,
                Map.of("recallAt5", 1.0), null, start, start.plusSeconds(3), start));

        var attempts = adapter.loadCaseAttempts(resumedRunId, caseId);
        assertEquals(1, attempts.size());
        assertEquals(attemptId, attempts.getFirst().id());
        assertEquals("SUCCEEDED", attempts.getFirst().status());
        assertEquals(1.0, attempts.getFirst().metrics().get("recallAt5"));
        assertFalse(attempts.getFirst().completedAt().isBefore(attempts.getFirst().startedAt()));
    }

    private static ReactKnowledgeReference reference(
            UUID id,
            UUID runId,
            UUID toolCallId,
            DocumentFixture document,
            KnowledgeReferenceSource source,
            boolean deepRead,
            double score
    ) {
        return new ReactKnowledgeReference(
                id, runId, toolCallId, document.knowledgeBaseId(), document.documentId(),
                document.versionId(), document.chunkId(), document.title(), document.text(), 0,
                document.text().length(), source, List.of(source), deepRead, score,
                Map.of("origin", source.name()), null, null, null, null);
    }

    private static RunFixture fixture(String prefix) {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, prefix);
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, 'test', 'Test User', 'EDITOR')
                """, userId, organizationId, prefix + "-" + userId);
        dsl.execute("""
                INSERT INTO conversation (id, organization_id, title, created_by)
                VALUES (?, ?, ?, ?)
                """, conversationId, organizationId, prefix, userId);
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, ?, '', '{}'::jsonb, now(), now())
                """, knowledgeBaseId, organizationId, prefix + " knowledge");
        dsl.execute("""
                INSERT INTO rag_run
                    (id, conversation_id, organization_id, requested_mode, selected_mode, query_text,
                     scope, filters, status, pipeline_version, prompt_version, created_by, started_at)
                VALUES (?, ?, ?, 'DEEP', 'DEEP', ?, '{}'::jsonb, '[]'::jsonb, 'RUNNING',
                        'agentic-react-v1', 'weknora-progressive-rag-v1', ?, now())
                """, runId, conversationId, organizationId, prefix + " question", userId);
        return new RunFixture(organizationId, userId, conversationId, knowledgeBaseId, runId);
    }

    private static DocumentFixture document(RunFixture fixture, String title, String text) {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var chunkId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO document
                    (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                """, documentId, fixture.knowledgeBaseId(), fixture.organizationId(), title);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, content_hash, status, published_at)
                VALUES (?, ?, 1, ?, repeat('a', 64), 'PUBLISHED', now())
                """, versionId, documentId, title + ".txt");
        dsl.execute("UPDATE document SET current_version_id = ? WHERE id = ?", versionId, documentId);
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, chunk_type, order_index, chunk_text, embedding_text,
                     estimated_tokens, chunk_hash, chunk_policy_version)
                VALUES (?, ?, 'CHILD', 0, ?, ?, 10, repeat('b', 64), 'test-v1')
                """, chunkId, versionId, text, text);
        return new DocumentFixture(fixture.knowledgeBaseId(), documentId, versionId, chunkId, title, text);
    }

    private record RunFixture(
            UUID organizationId,
            UUID userId,
            UUID conversationId,
            UUID knowledgeBaseId,
            UUID runId
    ) {
    }

    private record DocumentFixture(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            UUID chunkId,
            String title,
            String text
    ) {
    }
}
