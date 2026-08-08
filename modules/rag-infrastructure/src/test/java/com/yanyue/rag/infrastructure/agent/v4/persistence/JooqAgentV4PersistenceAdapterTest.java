package com.yanyue.rag.infrastructure.agent.v4.persistence;

import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.ActionStatus;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.BudgetReservation;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.ChunkSourceSegment;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.Evidence;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.EvidenceRequirement;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.ExternalAction;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.GoalResearchOutcome;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.LogicalModelCall;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.ModelAttempt;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.ReservationStatus;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.ResearchPhase;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.RetrievalCandidate;
import static com.yanyue.rag.infrastructure.agent.v4.persistence.AgentV4PersistenceRecords.RetrievalTask;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.infrastructure.persistence.JooqRunRecordAdapter;
import com.yanyue.rag.infrastructure.persistence.JooqEvaluationRepository;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.BudgetReservationStatus;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v4.SearchQuery;
import com.yanyue.rag.domain.agent.v4.SearchQueryRole;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class JooqAgentV4PersistenceAdapterTest {
    private static PostgreSQLContainer postgres;
    private static DSLContext dsl;
    private static ObjectMapper objectMapper;
    private static JooqAgentV4PersistenceAdapter adapter;
    private static JooqAgenticV4RecoveryAdapter recoveryAdapter;

    @BeforeAll
    static void startDatabase() {
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
        adapter = new JooqAgentV4PersistenceAdapter(dsl, objectMapper);
        recoveryAdapter = new JooqAgenticV4RecoveryAdapter(dsl, objectMapper);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void migrationKeepsHistoricalCheckpointsReadableAndSeparatesV3Writes() {
        var v1 = runFixture("checkpoint-v1");
        var v2 = runFixture("checkpoint-v2");
        var v3 = runFixture("checkpoint-v3");

        dsl.execute("""
                INSERT INTO agent_run_checkpoint (run_id, checkpoint_version, stage, state)
                VALUES (?, 1, 'RETRIEVE', '{}'::jsonb),
                       (?, 2, 'OBSERVE', '{}'::jsonb)
                """, v1.runId(), v2.runId());
        adapter.saveCheckpoint(v3.runId(), "PRIMARY_RESEARCH", Map.of("stage", "PRIMARY_RESEARCH"));

        assertEquals(1, checkpointVersion(v1.runId()));
        assertEquals(2, checkpointVersion(v2.runId()));
        assertEquals(3, checkpointVersion(v3.runId()));
        assertThrows(IllegalStateException.class,
                () -> adapter.saveCheckpoint(v1.runId(), "PLAN", Map.of()));
        assertThrows(DataAccessException.class, () -> dsl.execute("""
                INSERT INTO agent_run_checkpoint (run_id, checkpoint_version, stage, state)
                VALUES (?, 5, 'PLAN', '{}'::jsonb)
                """, runFixture("checkpoint-invalid").runId()));
    }

    @Test
    void persistsExplicitV4FailureAndCancellationStopReasons() {
        var failed = runFixture("deadline-stop-reason");
        var cancelled = runFixture("cancel-stop-reason");
        var records = new JooqRunRecordAdapter(dsl, objectMapper);

        records.fail(failed.runId(), "Run Deadline 已耗尽", "DEADLINE_EXCEEDED");
        records.cancel(cancelled.runId());

        assertEquals("DEADLINE_EXCEEDED", dsl.fetchValue(
                "SELECT stop_reason FROM rag_run WHERE id = ?", failed.runId(), String.class));
        assertEquals("CANCELLED", dsl.fetchValue(
                "SELECT stop_reason FROM rag_run WHERE id = ?", cancelled.runId(), String.class));
    }

    @Test
    void persistsReversibleSourceMapAndEvidenceProvenanceIdempotently() {
        var fixture = documentFixture("source-map");
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var taskId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        var actionId = UUID.randomUUID();
        var segment = new ChunkSourceSegment(
                0, 0, fixture.text().length(), fixture.blockId(), 0, fixture.text().length(),
                100, 100 + fixture.text().length(), "UTF16_CODE_UNIT");

        adapter.saveChunkSourceMap(fixture.parentChunkId(), true, List.of(segment));
        assertEquals(List.of(segment), adapter.loadChunkSourceMap(fixture.parentChunkId()));
        assertEquals("MAPPED", value(
                "SELECT source_mapping_status FROM chunk WHERE id = ?",
                String.class, fixture.parentChunkId()));

        var reservation = new BudgetReservation(
                reservationId, fixture.runId(), "primary-search:" + taskId,
                Map.of("PHYSICAL_SEARCH", 1L), Map.of(), true,
                ReservationStatus.RESERVED);
        var action = new ExternalAction(
                actionId, fixture.runId(), goalId, "PRIMARY", "SEARCH", reservationId,
                ActionStatus.PENDING, null);
        var task = new RetrievalTask(
                taskId, fixture.runId(), goalId, ResearchPhase.PRIMARY, "INITIAL",
                "  Kafka   部署  ", "KEYWORD", List.of(requirementId));
        adapter.reserveRetrievalTask(reservation, action, task);
        adapter.saveRetrievalCandidate(new RetrievalCandidate(
                taskId, fixture.runId(), goalId, ResearchPhase.PRIMARY,
                fixture.parentChunkId(), 1, 0.91, "KEYWORD", 1, 0.95));

        String spanId = "a".repeat(64);
        UUID initialEvidenceId = UUID.randomUUID();
        var evidence = evidence(fixture, initialEvidenceId, goalId, spanId, 0.91);
        UUID persistedId = adapter.saveEvidence(
                evidence,
                List.of(new EvidenceRequirement(
                        requirementId, ResearchPhase.PRIMARY, null, "CONTRIBUTES")),
                List.of(taskId));
        UUID duplicateId = adapter.saveEvidence(
                evidence(fixture, UUID.randomUUID(), goalId, spanId, 0.99),
                List.of(new EvidenceRequirement(
                        requirementId, ResearchPhase.REPAIR, UUID.randomUUID(), "COMPLETE")),
                List.of(taskId));

        assertEquals(initialEvidenceId, persistedId);
        assertEquals(initialEvidenceId, duplicateId);
        assertEquals(1, dsl.fetchCount(DSL.table("evidence_item"),
                DSL.field("run_id").eq(fixture.runId())));
        assertEquals("COMPLETE", value("""
                SELECT target_effect FROM evidence_requirement
                WHERE evidence_id = ? AND requirement_id = ?
                """, String.class, initialEvidenceId, requirementId));
        assertEquals("REPAIR", value("""
                SELECT accepted_phase FROM evidence_requirement
                WHERE evidence_id = ? AND requirement_id = ?
                """, String.class, initialEvidenceId, requirementId));
        assertEquals(1, dsl.fetchCount(DSL.table("evidence_query_source"),
                DSL.field("evidence_id").eq(initialEvidenceId)));
        assertEquals(1, dsl.fetchCount(DSL.table("retrieval_query_candidate"),
                DSL.field("retrieval_task_id").eq(taskId)));
        assertEquals("kafka 部署", value("""
                SELECT normalized_query FROM agent_retrieval_task WHERE id = ?
                """, String.class, taskId));
    }

    @Test
    void rebuildsLedgerAndModelAttemptsAcrossBarrier() {
        var fixture = runFixture("recovery-ledger");
        var goalId = UUID.randomUUID();
        var callId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        var actionId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();
        var reservation = new BudgetReservation(
                reservationId, fixture.runId(), "deep-read:" + callId,
                Map.of(
                        "GENERATIVE_LLM_PHYSICAL_ATTEMPT", 1L,
                        "GENERATIVE_LLM_INPUT_TOKEN", 500L,
                        "GENERATIVE_LLM_OUTPUT_TOKEN", 100L),
                Map.of(), true, ReservationStatus.RESERVED);
        var action = new ExternalAction(
                actionId, fixture.runId(), goalId, "PRIMARY", "DEEP_READ", reservationId,
                ActionStatus.PENDING, null);
        var pendingCall = logicalCall(fixture.runId(), goalId, callId, ActionStatus.PENDING, 0, 0);
        var pendingAttempt = new ModelAttempt(
                attemptId, callId, 1, reservationId, ActionStatus.PENDING,
                500, 100, true, 0, null);

        adapter.reserveModelAttempt(reservation, action, pendingCall, pendingAttempt);
        assertTrue(adapter.claimAction(actionId));
        assertFalse(adapter.claimAction(actionId));
        adapter.saveModelAttempt(new ModelAttempt(
                attemptId, callId, 1, reservationId, ActionStatus.SUCCEEDED,
                420, 80, false, 1250, null));
        adapter.saveLogicalModelCall(logicalCall(
                fixture.runId(), goalId, callId, ActionStatus.SUCCEEDED, 420, 80));
        adapter.reconcileAction(actionId, ActionStatus.SUCCEEDED, Map.of(
                "GENERATIVE_LLM_PHYSICAL_ATTEMPT", 1L,
                "GENERATIVE_LLM_INPUT_TOKEN", 420L,
                "GENERATIVE_LLM_OUTPUT_TOKEN", 80L), false, null);
        adapter.saveGoalResearchOutcome(new GoalResearchOutcome(
                UUID.randomUUID(), fixture.runId(), goalId, ResearchPhase.PRIMARY, "SUCCEEDED",
                List.of(), callId, List.of(), "COMPLETED_EMPTY", false,
                Instant.parse("2026-07-23T08:00:00Z")));
        adapter.saveGoalResearchOutcome(new GoalResearchOutcome(
                UUID.randomUUID(), fixture.runId(), goalId, ResearchPhase.PRIMARY, "FAILED",
                List.of(), callId, List.of(), "SYSTEM_FAILURE", true,
                Instant.parse("2026-07-23T08:01:00Z")));
        adapter.saveModelAttempt(new ModelAttempt(
                attemptId, callId, 1, reservationId, ActionStatus.PENDING,
                999, 999, true, 9999, "LATE_CALLBACK"));
        adapter.saveLogicalModelCall(logicalCall(
                fixture.runId(), goalId, callId, ActionStatus.PENDING, 999, 999));

        var recovered = adapter.loadRecoveryState(fixture.runId());
        assertEquals(1, recovered.goalOutcomes().size());
        assertEquals("COMPLETED_EMPTY", recovered.goalOutcomes().getFirst().outcomeCategory());
        assertEquals(ReservationStatus.SUCCEEDED, recovered.reservations().getFirst().status());
        assertEquals(420L, recovered.reservations().getFirst().actualUsage()
                .get("GENERATIVE_LLM_INPUT_TOKEN"));
        assertEquals(ActionStatus.SUCCEEDED, recovered.actions().getFirst().status());
        assertEquals(1, recovered.logicalCalls().getFirst().attemptCount());
        assertEquals(420, recovered.logicalCalls().getFirst().inputTokens());
        assertEquals(ActionStatus.SUCCEEDED, recovered.attempts().getFirst().status());
        assertEquals(420, recovered.attempts().getFirst().inputTokens());
        assertEquals(80, recovered.attempts().getFirst().outputTokens());
        assertFalse(recovered.attempts().getFirst().tokenUsageEstimated());
        assertEquals(1250, recovered.attempts().getFirst().latencyMs());
    }

    @Test
    void searchTaskFollowsClaimAndCompletionState() {
        var fixture = runFixture("search-lifecycle");
        var goalId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var reservation = domainReservation("search:" + queryId, Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L));
        var query = new SearchQuery(queryId, goalId,
                com.yanyue.rag.domain.agent.v4.ResearchPhase.PRIMARY, SearchQueryRole.INITIAL,
                "Kafka 部署", SearchMode.KEYWORD, java.util.Set.of(requirementId));

        adapter.reserveSearch(fixture.runId(), reservation, query);
        assertEquals("PENDING", value(
                "SELECT status FROM agent_retrieval_task WHERE id = ?", String.class, queryId));
        assertTrue(adapter.claimSearch(fixture.runId(), reservation.reservationId()));
        assertEquals("RUNNING", value(
                "SELECT status FROM agent_retrieval_task WHERE id = ?", String.class, queryId));

        adapter.completeSearch(fixture.runId(), reservation.reservationId(), true, 7, null);

        assertEquals("SUCCEEDED", value(
                "SELECT status FROM agent_retrieval_task WHERE id = ?", String.class, queryId));
        assertEquals(7, value(
                "SELECT result_count FROM agent_retrieval_task WHERE id = ?", Integer.class, queryId));
    }

    @Test
    void genericExternalOperationPersistsReservationAndLifecycle() {
        var fixture = runFixture("rerank-lifecycle");
        var goalId = UUID.randomUUID();
        var reservation = domainReservation("rerank:PRIMARY:" + goalId,
                Map.of(BudgetDimension.RERANK_CALL, 1L));

        adapter.reserveOperation(fixture.runId(), goalId, "PRIMARY", "RERANK", reservation);
        assertTrue(adapter.claimOperation(reservation.reservationId()));
        adapter.completeOperation(reservation.reservationId(), true, null);

        var recovered = adapter.loadRecoveryState(fixture.runId());
        assertEquals(ReservationStatus.SUCCEEDED, recovered.reservations().getFirst().status());
        assertEquals(ActionStatus.SUCCEEDED, recovered.actions().getFirst().status());
        assertEquals("RERANK", recovered.actions().getFirst().operation());
    }

    @Test
    void evaluationReadsV4QueryCandidatesAndCoverageState() {
        var fixture = documentFixture("evaluation-v4");
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var reservation = domainReservation("search:" + queryId,
                Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L));
        var query = new SearchQuery(queryId, goalId,
                com.yanyue.rag.domain.agent.v4.ResearchPhase.PRIMARY, SearchQueryRole.INITIAL,
                "部署步骤", SearchMode.KEYWORD, java.util.Set.of(requirementId));
        adapter.reserveSearch(fixture.runId(), reservation, query);
        assertTrue(adapter.claimSearch(fixture.runId(), reservation.reservationId()));
        adapter.saveRetrievalCandidates(fixture.runId(), query, List.of(new RetrievalHit(
                fixture.parentChunkId(), null, fixture.documentId(), fixture.versionId(), "部署文档",
                fixture.text(), 0.9, List.of("keyword"), 1, 100, 100 + fixture.text().length())));
        adapter.completeSearch(fixture.runId(), reservation.reservationId(), true, 1, null);
        adapter.saveCheckpoint(fixture.runId(), "COMPLETED", Map.of("coverage", Map.of(
                "requirementStatuses", Map.of(requirementId.toString(), "COVERED"))));

        var evaluation = new JooqEvaluationRepository(dsl, objectMapper);
        var candidates = evaluation.findRagRunCandidates(fixture.organizationId(), fixture.runId());
        var diagnostics = evaluation.findRagRunRetrievalDiagnostics(
                fixture.organizationId(), fixture.runId());

        assertEquals(fixture.documentId(), candidates.getFirst().documentId());
        assertEquals(1, ((Number) diagnostics.get("physicalSearchCount")).intValue());
        assertEquals(1, ((Number) diagnostics.get("coveredRequirementCount")).intValue());
        assertEquals(0, ((Number) diagnostics.get("evidenceLinkedRequirementCount")).intValue());
    }

    @Test
    void aggregatesPhysicalAttemptsWithoutOverwritingLogicalMetadata() {
        var fixture = runFixture("model-lifecycle");
        var goalId = UUID.randomUUID();
        var logicalCallId = UUID.randomUUID();
        var first = domainReservation("deep-read:PRIMARY:" + goalId + ":attempt-1", modelUsage(500, 100));
        var second = domainReservation("deep-read:PRIMARY:" + goalId + ":attempt-2", modelUsage(350, 80));

        adapter.reserveModelAttempt(fixture.runId(), logicalCallId, goalId, "PRIMARY",
                "agentic-v4-deep-read", "agentic-v4-deep-read-v1", 1, first, 2_000);
        assertTrue(adapter.claimModelAttempt(first.reservationId()));
        adapter.completeModelAttempt(logicalCallId, first.reservationId(), 1,
                true, false, false, 420, 60, 900, null, "a".repeat(64));
        adapter.reserveModelAttempt(fixture.runId(), logicalCallId, goalId, "PRIMARY",
                "agentic-v4-deep-read", "agentic-v4-deep-read-v1", 2, second, 1_400);
        assertTrue(adapter.claimModelAttempt(second.reservationId()));
        adapter.completeModelAttempt(logicalCallId, second.reservationId(), 2,
                true, true, true, 310, 40, 600, null, "b".repeat(64));
        adapter.completeLogicalModelCall(logicalCallId, true, true, null, "b".repeat(64));

        var recovered = adapter.loadRecoveryState(fixture.runId());
        var logical = recovered.logicalCalls().getFirst();
        assertEquals("agentic-v4-deep-read", logical.operation());
        assertEquals("agentic-v4-deep-read-v1", logical.promptVersion());
        assertEquals(2, logical.attemptCount());
        assertTrue(logical.repairUsed());
        assertEquals(770, logical.inputTokens());
        assertEquals(140, logical.outputTokens());
        assertEquals(1500, logical.latencyMs());
        assertEquals(ActionStatus.SUCCEEDED, logical.status());
        assertEquals(2, recovered.attempts().size());
        assertTrue(recovered.attempts().stream().anyMatch(ModelAttempt::tokenUsageEstimated));
    }

    @Test
    void v4RecoveryConsumesRunningActionsAndKeepsSchemaSeparated() {
        var fixture = runFixture("v4-recovery");
        var goalId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var reservation = domainReservation("search:" + queryId, Map.of(BudgetDimension.PHYSICAL_SEARCH, 1L));
        var query = new SearchQuery(queryId, goalId,
                com.yanyue.rag.domain.agent.v4.ResearchPhase.PRIMARY, SearchQueryRole.INITIAL,
                "恢复检索", SearchMode.KEYWORD, java.util.Set.of(requirementId));
        adapter.saveCheckpoint(fixture.runId(), "PRIMARY_RESEARCH", Map.of(
                "analysis", Map.of("standaloneObjective", "恢复检索")));
        adapter.reserveSearch(fixture.runId(), reservation, query);
        assertTrue(adapter.claimSearch(fixture.runId(), reservation.reservationId()));

        recoveryAdapter.prepareForRecovery(fixture.runId());
        var snapshot = recoveryAdapter.loadSnapshot(fixture.runId()).orElseThrow();

        assertTrue(recoveryAdapter.findRecoverableRuns().stream()
                .anyMatch(value -> value.runId().equals(fixture.runId())));
        assertEquals("PRIMARY_RESEARCH", snapshot.stage());
        assertTrue(snapshot.nonReplayableActionKeys().contains("search:" + queryId));
        assertEquals(BudgetReservationStatus.FAILED, snapshot.reservations().getFirst().status());
        assertEquals("FAILED", value(
                "SELECT status FROM agent_retrieval_task WHERE id = ?", String.class, queryId));
        assertEquals("FAILED", value(
                "SELECT status FROM agent_external_action WHERE reservation_id = ?",
                String.class, reservation.reservationId()));
    }

    private static Map<BudgetDimension, Long> modelUsage(long input, long output) {
        return Map.of(
                BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT, 1L,
                BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN, input,
                BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN, output);
    }

    private static com.yanyue.rag.domain.agent.v4.BudgetReservation domainReservation(
            String actionKey,
            Map<BudgetDimension, Long> usage
    ) {
        var now = Instant.parse("2026-07-23T08:00:00Z");
        return new com.yanyue.rag.domain.agent.v4.BudgetReservation(
                UUID.randomUUID(), actionKey, usage, BudgetReservationStatus.RESERVED, now, now);
    }

    private static LogicalModelCall logicalCall(
            UUID runId,
            UUID goalId,
            UUID callId,
            ActionStatus status,
            long inputTokens,
            long outputTokens
    ) {
        return new LogicalModelCall(
                callId, runId, goalId, "PRIMARY", "DEEP_READ", "deep-read-v4",
                "deep-read-contract-v1", "b".repeat(64), 800, 1, false,
                inputTokens, outputTokens, 1250, status, null, "c".repeat(64));
    }

    private static Evidence evidence(
            DocumentFixture fixture,
            UUID evidenceId,
            UUID goalId,
            String spanId,
            double score
    ) {
        return new Evidence(
                evidenceId, fixture.runId(), goalId, fixture.documentId(), fixture.versionId(),
                fixture.parentChunkId(), spanId, fixture.text(), 100,
                100 + fixture.text().length(), score, ResearchPhase.PRIMARY,
                Map.of(
                        "parentChunkId", fixture.parentChunkId().toString(),
                        "parentLocalStart", 0,
                        "parentLocalEnd", fixture.text().length(),
                        "parentOffsetUnit", "UTF16_CODE_UNIT"),
                List.of("KEYWORD"));
    }

    private static int checkpointVersion(UUID runId) {
        return value("""
                SELECT checkpoint_version FROM agent_run_checkpoint WHERE run_id = ?
                """, Integer.class, runId);
    }

    private static <T> T value(String sql, Class<T> type, Object... bindings) {
        return dsl.fetchSingle(sql, bindings).get(0, type);
    }

    private static RunFixture runFixture(String prefix) {
        var organizationId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, prefix);
        dsl.execute("""
                INSERT INTO rag_run
                    (id, organization_id, requested_mode, selected_mode, query_text, status,
                     pipeline_version, prompt_version, started_at)
                VALUES (?, ?, 'DEEP', 'DEEP', ?, 'RUNNING',
                        'agentic-rag-v4', 'agentic-rag-v4', now())
                """, runId, organizationId, prefix);
        return new RunFixture(organizationId, runId);
    }

    private static DocumentFixture documentFixture(String prefix) {
        var run = runFixture(prefix);
        var knowledgeBaseId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        var parentChunkId = UUID.randomUUID();
        String text = "部署😀步骤";
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, chunk_policy, created_at, updated_at)
                VALUES (?, ?, ?, '{}'::jsonb, now(), now())
                """, knowledgeBaseId, run.organizationId(), prefix);
        dsl.execute("""
                INSERT INTO document
                    (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                """, documentId, knowledgeBaseId, run.organizationId(), prefix);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, content_hash, status, published_at)
                VALUES (?, ?, 1, ?, repeat('a', 64), 'PUBLISHED', now())
                """, versionId, documentId, prefix + ".txt");
        dsl.execute("UPDATE document SET current_version_id = ? WHERE id = ?", versionId, documentId);
        dsl.execute("""
                INSERT INTO document_block
                    (id, document_version_id, block_type, order_index, block_text,
                     source_start, source_end, block_hash)
                VALUES (?, ?, 'PARAGRAPH', 0, ?, 100, ?, repeat('b', 64))
                """, blockId, versionId, text, 100 + text.length());
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, chunk_type, order_index, chunk_text, embedding_text,
                     estimated_tokens, source_block_ids, chunk_hash, chunk_policy_version)
                VALUES (?, ?, 'PARENT', 0, ?, ?, 10, ?, repeat('c', 64), 'v4-test')
                """, parentChunkId, versionId, text, text, new UUID[]{blockId});
        return new DocumentFixture(
                run.organizationId(), run.runId(), documentId, versionId, blockId,
                parentChunkId, text);
    }

    private record RunFixture(UUID organizationId, UUID runId) {
    }

    private record DocumentFixture(
            UUID organizationId,
            UUID runId,
            UUID documentId,
            UUID versionId,
            UUID blockId,
            UUID parentChunkId,
            String text
    ) {
    }
}
