package com.yanyue.rag.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.evaluation.EvaluationRunStatus;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.security.CredentialRotationAudit;
import java.time.Instant;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

class JooqEvaluationRepositoryTest {
    private static PostgreSQLContainer postgres;
    private static DSLContext dsl;

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

    @Test
    void readsAndRotatesEveryEncryptedCredentialStore() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var profileId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var fastRunId = UUID.randomUUID();
        var deepRunId = UUID.randomUUID();
        var comparisonId = UUID.randomUUID();
        var scheduleId = UUID.randomUUID();
        var deliveryId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "rotation-test");
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, 'test', 'Rotation Admin', 'ADMIN')
                """, userId, organizationId, "rotation-" + userId);
        dsl.execute("""
                INSERT INTO model_profile
                    (id, organization_id, profile_type, provider, name, model_name, encrypted_api_key)
                VALUES (?, ?, 'CHAT', 'OPENAI_COMPATIBLE', 'rotation-chat', 'test', ?)
                """, profileId, organizationId, "v1:model".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        dsl.execute("INSERT INTO evaluation_dataset (id, organization_id, name) VALUES (?, ?, 'rotation-data')",
                datasetId, organizationId);
        dsl.execute("""
                INSERT INTO evaluation_run (id, dataset_id, status)
                VALUES (?, ?, 'COMPLETED'), (?, ?, 'COMPLETED')
                """, fastRunId, datasetId, deepRunId, datasetId);
        dsl.execute("""
                INSERT INTO evaluation_comparison
                    (id, dataset_id, fast_run_id, deep_run_id, judge_mode, created_by)
                VALUES (?, ?, ?, ?, 'NONE', ?)
                """, comparisonId, datasetId, fastRunId, deepRunId, userId);
        dsl.execute("""
                INSERT INTO evaluation_schedule
                    (id, organization_id, dataset_id, created_by, name, cadence_minutes, request,
                     next_run_at, webhook_url, webhook_secret_ciphertext)
                VALUES (?, ?, ?, ?, 'rotation-schedule', 60, '{}'::jsonb, now(),
                        'https://example.test/hook', 'v1:schedule')
                """, scheduleId, organizationId, datasetId, userId);
        dsl.execute("""
                INSERT INTO evaluation_notification_delivery
                    (id, organization_id, schedule_id, comparison_id, dataset_id, schedule_name,
                     dataset_name, webhook_url, webhook_secret_ciphertext, status)
                VALUES (?, ?, ?, ?, ?, 'rotation-schedule', 'rotation-data',
                        'https://example.test/hook', 'v1:delivery', 'WAITING')
                """, deliveryId, organizationId, scheduleId, comparisonId, datasetId);

        dsl.transaction(configuration -> {
            var repository = new JooqCredentialRotationRepository(
                    org.jooq.impl.DSL.using(configuration), new ObjectMapper());
            repository.lockCredentialStores();
            var credentials = repository.findAllCredentials().stream()
                    .filter(value -> List.of(profileId, scheduleId, deliveryId).contains(value.id()))
                    .toList();
            assertEquals(3, credentials.size());
            credentials.forEach(value -> repository.updateCredential(
                    value, "v2:current:" + value.location()));
            var audit = new CredentialRotationAudit(
                    UUID.randomUUID(), "current", userId, 3, 3,
                    Map.of("MODEL_PROFILE", 1, "EVALUATION_SCHEDULE", 1, "EVALUATION_DELIVERY", 1),
                    Map.of("legacy-v1", 3), Instant.parse("2026-07-13T14:00:00Z"));
            repository.saveAudit(audit);

            assertTrue(repository.findAllCredentials().stream()
                    .filter(value -> List.of(profileId, scheduleId, deliveryId).contains(value.id()))
                    .allMatch(value -> value.ciphertext().startsWith("v2:current:")));
            var persistedAudit = repository.findLatestAudit().orElseThrow();
            assertEquals(audit.id(), persistedAudit.id());
            assertEquals(3, persistedAudit.rotatedCredentials());
            assertEquals(Map.of("legacy-v1", 3), persistedAudit.previousKeyCounts());
        });
    }

    @Test
    void persistsHiddenConversationAndLinksTheCompletedRagRun() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var ragRunId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "evaluation-test");
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, 'test', 'Evaluator', 'EDITOR')
                """, userId, organizationId, "evaluator-" + userId);

        var repository = new JooqEvaluationRepository(dsl, new ObjectMapper());
        var dataset = repository.createDataset(organizationId, "release checks", "");
        var evaluationCase = repository.addCase(
                organizationId, dataset.id(), "Which policy applies?", "current policy", List.of(), Map.of());
        var followUpCase = repository.addCase(
                organizationId, dataset.id(), "And what follows?", "next policy", List.of(), Map.of());
        var orderedCases = repository.findCases(organizationId, dataset.id());
        assertEquals(List.of(evaluationCase.id(), followUpCase.id()),
                orderedCases.stream().map(value -> value.id()).toList());
        assertTrue(orderedCases.getFirst().position() < orderedCases.getLast().position());
        var evaluationRun = repository.createRun(organizationId, dataset.id());
        var deepEvaluationRun = repository.createRun(
                organizationId, dataset.id(), Map.of("requestedMode", "DEEP"));
        var comparison = repository.createComparison(
                organizationId, userId, dataset.id(), evaluationRun.id(), deepEvaluationRun.id(), "NONE");
        repository.markRunRunning(evaluationRun.id());
        var conversationId = repository.createEvaluationConversation(
                organizationId, userId, evaluationRun.id());

        assertEquals("EVALUATION", dsl.fetchValue(
                "SELECT conversation_kind FROM conversation WHERE id = ?", conversationId, String.class));

        dsl.execute("""
                INSERT INTO rag_run
                    (id, conversation_id, organization_id, requested_mode, selected_mode, query_text,
                     status, pipeline_version, prompt_version, runtime_snapshot, started_at, completed_at)
                VALUES (?, ?, ?, 'FAST', 'FAST', 'Which policy applies?', 'COMPLETED',
                        'fast-test', 'prompt-test', '{"chatModel":"test"}'::jsonb,
                        now() - interval '1 second', now())
                """, ragRunId, conversationId, organizationId);
        dsl.execute("""
                INSERT INTO conversation_message (conversation_id, role, content, metadata)
                VALUES (?, 'assistant', 'The current policy applies.', jsonb_build_object('runId', ?::text))
                """, conversationId, ragRunId);

        var outcome = repository.findRagRunOutcome(organizationId, ragRunId).orElseThrow();
        assertEquals("COMPLETED", outcome.status());
        assertEquals("The current policy applies.", outcome.answer());
        assertEquals(0, outcome.citationCount());
        assertEquals("test", outcome.runtimeSnapshot().get("chatModel"));

        repository.saveResult(evaluationRun.id(), evaluationCase.id(), ragRunId,
                Map.of("execution", "RAG"), null);
        repository.completeRun(evaluationRun.id(), Map.of("execution", "RAG"));

        var results = repository.findResults(organizationId, evaluationRun.id());
        assertEquals(1, results.size());
        assertEquals(ragRunId, results.getFirst().ragRunId());
        assertTrue(repository.findRun(organizationId, evaluationRun.id()).isPresent());
        assertEquals("DEEP", repository.findRun(organizationId, deepEvaluationRun.id())
                .orElseThrow().aggregateMetrics().get("requestedMode"));
        assertEquals(evaluationRun.id(), repository.findComparison(organizationId, comparison.id())
                .orElseThrow().fastRunId());
    }

    @Test
    void cancellationIsTenantScopedPersistentAndTerminal() {
        var organizationId = UUID.randomUUID();
        var otherOrganizationId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "cancellation-test");
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)",
                otherOrganizationId, "other-cancellation-test");

        var repository = new JooqEvaluationRepository(dsl, new ObjectMapper());
        var dataset = repository.createDataset(organizationId, "Cancellation checks", "");
        var queuedRun = repository.createRun(
                organizationId, dataset.id(), Map.of("execution", "RAG"));

        assertEquals(false, repository.cancelRun(otherOrganizationId, queuedRun.id()));
        assertEquals(false, repository.isRunCancellationRequested(queuedRun.id()));
        assertTrue(repository.cancelRun(organizationId, queuedRun.id()));
        assertTrue(repository.isRunCancellationRequested(queuedRun.id()));
        assertCancelled(repository, organizationId, queuedRun.id());

        repository.markRunRunning(queuedRun.id());
        repository.completeRun(queuedRun.id(), Map.of("successfulCases", 1));
        repository.failRun(queuedRun.id(), "late failure");
        assertCancelled(repository, organizationId, queuedRun.id());
        assertEquals(false, repository.cancelRun(organizationId, queuedRun.id()));

        var runningRun = repository.createRun(organizationId, dataset.id());
        repository.markRunRunning(runningRun.id());
        assertEquals(EvaluationRunStatus.RUNNING, repository.findRun(organizationId, runningRun.id())
                .orElseThrow().status());
        assertTrue(repository.cancelRun(organizationId, runningRun.id()));
        repository.completeRun(runningRun.id(), Map.of("successfulCases", 1));
        repository.failRun(runningRun.id(), "late failure");
        assertCancelled(repository, organizationId, runningRun.id());
    }

    private static void assertCancelled(
            JooqEvaluationRepository repository,
            UUID organizationId,
            UUID runId
    ) {
        var run = repository.findRun(organizationId, runId).orElseThrow();
        assertEquals(EvaluationRunStatus.CANCELLED, run.status());
        assertEquals(true, run.aggregateMetrics().get("cancelled"));
        assertEquals("cancelled-by-user", run.aggregateMetrics().get("cancelReason"));
        assertTrue(run.completedAt() != null);
    }

    @Test
    void fallsBackToDeepEvidenceWhenTheRunHasNoFastRetrievalCandidates() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var documentVersionId = UUID.randomUUID();
        var chunkId = UUID.randomUUID();
        var runId = UUID.randomUUID();

        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "deep-evaluation-test");
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, 'Agent evidence', '', '{}'::jsonb, now(), now())
                """, knowledgeBaseId, organizationId);
        dsl.execute("""
                INSERT INTO document
                    (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Disaster recovery policy', 'ACTIVE', now(), now())
                """, documentId, knowledgeBaseId, organizationId);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, content_hash, status, published_at)
                VALUES (?, ?, 1, 'dr-policy.pdf', repeat('a', 64), 'PUBLISHED', now())
                """, documentVersionId, documentId);
        dsl.execute("UPDATE document SET current_version_id = ? WHERE id = ?", documentVersionId, documentId);
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, chunk_type, order_index, chunk_text, embedding_text,
                     estimated_tokens, chunk_hash, chunk_policy_version)
                VALUES (?, ?, 'CHILD', 0, 'Restore the database before object storage.',
                        'Restore the database before object storage.', 9, repeat('b', 64), 'test-v1')
                """, chunkId, documentVersionId);
        dsl.execute("""
                INSERT INTO rag_run
                    (id, organization_id, requested_mode, selected_mode, query_text, status,
                     pipeline_version, prompt_version, started_at)
                VALUES (?, ?, 'DEEP', 'DEEP', 'What is the restore order?', 'RUNNING',
                        'agent-test', 'prompt-test', now())
                """, runId, organizationId);
        dsl.execute("""
                INSERT INTO evidence_item
                    (id, run_id, document_id, document_version_id, chunk_id, quote_text,
                     source_start, source_end, retrieval_score, deep_read, retrieval_sources)
                VALUES (?, ?, ?, ?, ?, 'Restore the database before object storage.',
                        0, 43, 0.91, true, ARRAY['semantic_search', 'chunk_read'])
                """, UUID.randomUUID(), runId, documentId, documentVersionId, chunkId);
        dsl.execute("""
                INSERT INTO citation
                    (run_id, citation_index, document_id, document_version_id, chunk_id, quote_text)
                VALUES (?, 1, ?, ?, ?, 'Restore the database before object storage.')
                """, runId, documentId, documentVersionId, chunkId);

        var repository = new JooqEvaluationRepository(dsl, new ObjectMapper());
        var candidates = repository.findRagRunCandidates(organizationId, runId);

        assertEquals(1, candidates.size());
        assertEquals(chunkId, candidates.getFirst().chunkId());
        assertEquals(documentId, candidates.getFirst().documentId());
        assertEquals(0.91, candidates.getFirst().score());
        assertTrue(candidates.getFirst().sources().contains("chunk_read"));
        assertEquals(0, candidates.getFirst().sourceStart());
        assertEquals(43, candidates.getFirst().sourceEnd());
        var citations = repository.findRagRunCitations(organizationId, runId);
        assertEquals(1, citations.size());
        assertEquals(chunkId, citations.getFirst().chunkId());
        assertEquals("Restore the database before object storage.", citations.getFirst().quote());
    }

    @Test
    void v5BalancesGoalRanksAndNeverLetsEvidenceReorderRetrieval() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var firstGoalId = UUID.randomUUID();
        var secondGoalId = UUID.randomUUID();

        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "v5-ranking-test");
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, 'V5 ranking', '', '{}'::jsonb, now(), now())
                """, knowledgeBaseId, organizationId);
        var first = insertRankedDocument(organizationId, knowledgeBaseId, "First");
        var second = insertRankedDocument(organizationId, knowledgeBaseId, "Second");
        var third = insertRankedDocument(organizationId, knowledgeBaseId, "Third");
        var fourth = insertRankedDocument(organizationId, knowledgeBaseId, "Fourth");
        var fifth = insertRankedDocument(organizationId, knowledgeBaseId, "Fifth");
        var evidenceOnly = insertRankedDocument(organizationId, knowledgeBaseId, "Evidence only");
        dsl.execute("""
                INSERT INTO rag_run
                    (id, organization_id, requested_mode, selected_mode, query_text, status,
                     pipeline_version, prompt_version, started_at)
                VALUES (?, ?, 'DEEP', 'DEEP', 'Compare several independent goals', 'RUNNING',
                        'agentic-rag-v5', 'prompt-v5', now())
                """, runId, organizationId);

        insertRankedCandidate(runId, firstGoalId, 1, "PRIMARY", first, 1, 0.99, "KEYWORD");
        insertRankedCandidate(runId, firstGoalId, 1, "PRIMARY", second, 2, 0.95, "KEYWORD");
        insertRankedCandidate(runId, firstGoalId, 1, "PRIMARY", third, 3, 0.90, "KEYWORD");
        insertRankedCandidate(runId, firstGoalId, 1, "REPAIR", second, 1, 0.98, "SEMANTIC");
        insertRankedCandidate(runId, firstGoalId, 1, "REPAIR", fourth, 2, 0.92, "SEMANTIC");
        insertRankedCandidate(runId, secondGoalId, 2, "PRIMARY", third, 1, 0.97, "SEMANTIC");
        insertRankedCandidate(runId, secondGoalId, 2, "PRIMARY", first, 2, 0.94, "SEMANTIC");
        insertRankedCandidate(runId, secondGoalId, 2, "PRIMARY", fifth, 3, 0.89, "SEMANTIC");

        var repository = new JooqEvaluationRepository(dsl, new ObjectMapper());
        var expectedOrder = List.of(
                second.documentId(), third.documentId(), first.documentId(), fourth.documentId(), fifth.documentId());
        var beforeEvidence = repository.findRagRunCandidates(organizationId, runId);
        assertEquals(expectedOrder, beforeEvidence.stream().map(value -> value.documentId()).toList());
        assertEquals(second.chunkId(), beforeEvidence.getFirst().chunkId());

        var evidenceId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO evidence_item
                    (id, run_id, sub_question_id, document_id, document_version_id, chunk_id,
                     quote_text, source_start, source_end, retrieval_score, deep_read, retrieval_sources)
                VALUES (?, ?, ?, ?, ?, ?, 'Evidence only', 0, 13, 100.0, true, ARRAY['evidence-span'])
                """, evidenceId, runId, secondGoalId, evidenceOnly.documentId(), evidenceOnly.versionId(),
                evidenceOnly.chunkId());
        assertEquals(expectedOrder, repository.findRagRunCandidates(organizationId, runId).stream()
                .map(value -> value.documentId()).toList());

        dsl.execute("DELETE FROM evidence_item WHERE id = ?", evidenceId);
        assertEquals(expectedOrder, repository.findRagRunCandidates(organizationId, runId).stream()
                .map(value -> value.documentId()).toList());
        var diagnostics = repository.findRagRunRetrievalDiagnostics(organizationId, runId);
        assertEquals("agent_goal_ranked_candidate", diagnostics.get("retrievalRankingSource"));
        assertEquals("GOAL_PHASE_RRF_BALANCED", diagnostics.get("retrievalProjection"));
        assertEquals(8, diagnostics.get("postRerankCandidateCount"));
        assertEquals(2, diagnostics.get("postRerankGoalCount"));
        assertEquals(6, diagnostics.get("primaryPostRerankCandidateCount"));
        assertEquals(2, diagnostics.get("repairPostRerankCandidateCount"));
    }

    @Test
    void v7ProjectsAcceptedEvidenceIntoBalancedGoalRanking() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var goalId = UUID.randomUUID();

        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "v7-ranking-test");
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, 'V7 ranking', '', '{}'::jsonb, now(), now())
                """, knowledgeBaseId, organizationId);
        var retrievalOnly = insertRankedDocument(organizationId, knowledgeBaseId, "Retrieval only");
        var evidenceBacked = insertRankedDocument(organizationId, knowledgeBaseId, "Evidence backed");
        dsl.execute("""
                INSERT INTO rag_run
                    (id, organization_id, requested_mode, selected_mode, query_text, status,
                     pipeline_version, prompt_version, started_at)
                VALUES (?, ?, 'DEEP', 'DEEP', 'v7 ranking', 'RUNNING',
                        'agentic-rag-v7', 'prompt-v7', now())
                """, runId, organizationId);
        insertRankedCandidate(runId, goalId, 1, "PRIMARY", retrievalOnly, 1, 0.99, "KEYWORD");
        insertRankedCandidate(runId, goalId, 1, "PRIMARY", evidenceBacked, 2, 0.80, "SEMANTIC");
        var evidenceId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO evidence_item
                    (id, run_id, sub_question_id, document_id, document_version_id, chunk_id,
                     quote_text, source_start, source_end, retrieval_score, deep_read, retrieval_sources)
                VALUES (?, ?, ?, ?, ?, ?, 'Evidence backed evidence', 0, 23, 0.80, true,
                        ARRAY['semantic'])
                """, evidenceId, runId, goalId, evidenceBacked.documentId(), evidenceBacked.versionId(),
                evidenceBacked.chunkId());
        dsl.execute("""
                INSERT INTO evidence_requirement (evidence_id, requirement_id, accepted_phase, target_effect)
                VALUES (?, ?, 'PRIMARY', 'COMPLETE')
                """, evidenceId, UUID.randomUUID());

        var repository = new JooqEvaluationRepository(dsl, new ObjectMapper());
        var candidates = repository.findRagRunCandidates(organizationId, runId);

        assertEquals(evidenceBacked.documentId(), candidates.getFirst().documentId());
        assertEquals("EVIDENCE_BOOLEAN_GOAL_BALANCED_V2",
                repository.findRagRunRetrievalDiagnostics(organizationId, runId).get("retrievalProjection"));
    }

    @Test
    void v7CoverageTextsOnlyExposeActiveCurrentDeepReadEvidence() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var goalId = UUID.randomUUID();
        insertV7RankingRun(organizationId, knowledgeBaseId, runId);

        var current = insertRankedDocument(organizationId, knowledgeBaseId, "Current evidence");
        var acceptedId = insertEvidence(
                runId, goalId, UUID.randomUUID(), current, "direct current fact");
        insertEvidence(runId, goalId, UUID.randomUUID(), current, "direct current fact");

        var superseded = insertRankedDocument(organizationId, knowledgeBaseId, "Superseded evidence");
        var supersededId = insertEvidence(
                runId, goalId, UUID.randomUUID(), superseded, "superseded fact");
        dsl.execute("""
                UPDATE evidence_requirement
                SET status = 'SUPERSEDED', superseded_by_evidence_id = ?
                WHERE evidence_id = ?
                """, acceptedId, supersededId);

        var shallow = insertRankedDocument(organizationId, knowledgeBaseId, "Shallow evidence");
        var shallowId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO evidence_item
                    (id, run_id, sub_question_id, document_id, document_version_id, chunk_id,
                     quote_text, source_start, source_end, retrieval_score, deep_read, retrieval_sources)
                VALUES (?, ?, ?, ?, ?, ?, 'shallow fact', 0, 12, 0.8, false, ARRAY['keyword'])
                """, shallowId, runId, goalId, shallow.documentId(), shallow.versionId(), shallow.chunkId());
        dsl.execute("""
                INSERT INTO evidence_requirement (evidence_id, requirement_id, accepted_phase, target_effect)
                VALUES (?, ?, 'PRIMARY', 'COMPLETE')
                """, shallowId, UUID.randomUUID());

        var staleVersion = insertRankedDocument(organizationId, knowledgeBaseId, "Stale version");
        insertEvidence(runId, goalId, UUID.randomUUID(), staleVersion, "stale version fact");
        var replacementVersionId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, content_hash, status, published_at)
                VALUES (?, ?, 2, 'replacement.md', ?, 'PUBLISHED', now())
                """, replacementVersionId, staleVersion.documentId(), "f".repeat(64));
        dsl.execute("UPDATE document SET current_version_id = ? WHERE id = ?",
                replacementVersionId, staleVersion.documentId());

        var inactive = insertRankedDocument(organizationId, knowledgeBaseId, "Inactive evidence");
        insertEvidence(runId, goalId, UUID.randomUUID(), inactive, "inactive fact");
        dsl.execute("UPDATE document SET status = 'INACTIVE' WHERE id = ?", inactive.documentId());

        var repository = new JooqEvaluationRepository(dsl, new ObjectMapper());

        assertEquals(List.of("direct current fact"),
                repository.findRagRunAcceptedEvidenceTexts(organizationId, runId));
        assertEquals(List.of(),
                repository.findRagRunAcceptedEvidenceTexts(UUID.randomUUID(), runId));
    }

    @Test
    void v7DoesNotRewardDuplicateEvidenceForTheSameRequirement() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        insertV7RankingRun(organizationId, knowledgeBaseId, runId);
        var betterRetrieval = insertRankedDocument(organizationId, knowledgeBaseId, "Better retrieval");
        var duplicateEvidence = insertRankedDocument(organizationId, knowledgeBaseId, "Duplicate evidence");
        insertRankedCandidate(runId, goalId, 1, "PRIMARY", betterRetrieval, 1, 0.99, "KEYWORD");
        insertRankedCandidate(runId, goalId, 1, "PRIMARY", duplicateEvidence, 2, 0.90, "SEMANTIC");
        insertEvidence(runId, goalId, requirementId, betterRetrieval, "one direct span");
        insertEvidence(runId, goalId, requirementId, duplicateEvidence, "first duplicate span");
        insertEvidence(runId, goalId, requirementId, duplicateEvidence, "second duplicate span");

        var candidates = new JooqEvaluationRepository(dsl, new ObjectMapper())
                .findRagRunCandidates(organizationId, runId);

        assertEquals(betterRetrieval.documentId(), candidates.getFirst().documentId());
        assertEquals(duplicateEvidence.documentId(), candidates.get(1).documentId());
    }

    @Test
    void v7BalancesThreeGoalsBeforeUsingTheNextDocumentFromAnEvidenceRichGoal() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var firstGoal = UUID.randomUUID();
        var secondGoal = UUID.randomUUID();
        var thirdGoal = UUID.randomUUID();
        insertV7RankingRun(organizationId, knowledgeBaseId, runId);
        var first = insertRankedDocument(organizationId, knowledgeBaseId, "Goal one first");
        var firstExtra = insertRankedDocument(organizationId, knowledgeBaseId, "Goal one extra");
        var second = insertRankedDocument(organizationId, knowledgeBaseId, "Goal two first");
        var third = insertRankedDocument(organizationId, knowledgeBaseId, "Goal three first");
        insertRankedCandidate(runId, firstGoal, 1, "PRIMARY", first, 1, 0.99, "KEYWORD");
        insertRankedCandidate(runId, firstGoal, 1, "PRIMARY", firstExtra, 2, 0.98, "SEMANTIC");
        insertRankedCandidate(runId, secondGoal, 2, "PRIMARY", second, 1, 0.97, "KEYWORD");
        insertRankedCandidate(runId, thirdGoal, 3, "PRIMARY", third, 1, 0.96, "SEMANTIC");
        var requirementId = UUID.randomUUID();
        insertEvidence(runId, firstGoal, requirementId, first, "goal one direct evidence");
        insertEvidence(runId, firstGoal, requirementId, firstExtra, "goal one extra direct evidence");

        var documentOrder = new JooqEvaluationRepository(dsl, new ObjectMapper())
                .findRagRunCandidates(organizationId, runId).stream()
                .map(RetrievalHit::documentId).toList();

        assertEquals(List.of(first.documentId(), second.documentId(), third.documentId(), firstExtra.documentId()),
                documentOrder);
    }

    @Test
    void v7BackfillsTheNextGoalCandidateAfterCrossGoalDocumentDeduplication() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var firstGoal = UUID.randomUUID();
        var secondGoal = UUID.randomUUID();
        insertV7RankingRun(organizationId, knowledgeBaseId, runId);
        var shared = insertRankedDocument(organizationId, knowledgeBaseId, "Shared");
        var firstNext = insertRankedDocument(organizationId, knowledgeBaseId, "First next");
        var secondNext = insertRankedDocument(organizationId, knowledgeBaseId, "Second next");
        insertRankedCandidate(runId, firstGoal, 1, "PRIMARY", shared, 1, 0.99, "KEYWORD");
        insertRankedCandidate(runId, firstGoal, 1, "PRIMARY", firstNext, 2, 0.90, "KEYWORD");
        insertRankedCandidate(runId, secondGoal, 2, "PRIMARY", shared, 1, 0.98, "SEMANTIC");
        insertRankedCandidate(runId, secondGoal, 2, "PRIMARY", secondNext, 2, 0.89, "SEMANTIC");

        var documentOrder = new JooqEvaluationRepository(dsl, new ObjectMapper())
                .findRagRunCandidates(organizationId, runId).stream()
                .map(RetrievalHit::documentId).toList();

        assertEquals(List.of(shared.documentId(), secondNext.documentId(), firstNext.documentId()), documentOrder);
    }

    @Test
    void v7NormalizesRerankAndRrfRankSpacesBeforeComparison() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var goalId = UUID.randomUUID();
        insertV7RankingRun(organizationId, knowledgeBaseId, runId);
        var reranked = insertRankedDocument(organizationId, knowledgeBaseId, "Reranked");
        var rrfOnly = insertRankedDocument(organizationId, knowledgeBaseId, "RRF only");
        insertRankedCandidateWithRanks(runId, goalId, 1, "PRIMARY", reranked,
                50, 0.01, 8, 0.70, "SEMANTIC");
        insertRankedCandidateWithRanks(runId, goalId, 1, "PRIMARY", rrfOnly,
                1, 0.99, null, null, "KEYWORD");

        var candidates = new JooqEvaluationRepository(dsl, new ObjectMapper())
                .findRagRunCandidates(organizationId, runId);

        assertEquals(rrfOnly.documentId(), candidates.getFirst().documentId());
        assertEquals(reranked.documentId(), candidates.get(1).documentId());
    }

    @Test
    void reportsAgenticHybridDiagnosticsFromV2RuntimeTables() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var documentVersionId = UUID.randomUUID();
        var chunkId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var subQuestionId = UUID.randomUUID();

        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "v2-diagnostics-test");
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, 'Agent diagnostics', '', '{}'::jsonb, now(), now())
                """, knowledgeBaseId, organizationId);
        dsl.execute("""
                INSERT INTO document
                    (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Incident response policy', 'ACTIVE', now(), now())
                """, documentId, knowledgeBaseId, organizationId);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, content_hash, status, published_at)
                VALUES (?, ?, 1, 'incident-policy.pdf', repeat('c', 64), 'PUBLISHED', now())
                """, documentVersionId, documentId);
        dsl.execute("UPDATE document SET current_version_id = ? WHERE id = ?", documentVersionId, documentId);
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, chunk_type, order_index, chunk_text, embedding_text,
                     estimated_tokens, chunk_hash, chunk_policy_version)
                VALUES (?, ?, 'CHILD', 0, 'Escalate severity-one incidents within fifteen minutes.',
                        'Escalate severity-one incidents within fifteen minutes.', 10,
                        repeat('d', 64), 'test-v1')
                """, chunkId, documentVersionId);
        dsl.execute("""
                INSERT INTO rag_run
                    (id, organization_id, requested_mode, selected_mode, query_text, status,
                     pipeline_version, prompt_version, started_at)
                VALUES (?, ?, 'AUTO', 'DEEP', 'How are severity-one incidents escalated?', 'RUNNING',
                        'agentic-hybrid-v2', 'prompt-v2', now() - interval '1 second')
                """, runId, organizationId);
        dsl.execute("""
                INSERT INTO agent_retrieval_task
                    (id, run_id, sub_question_id, round_number, query_text, search_mode,
                     status, result_count, started_at, completed_at)
                VALUES (?, ?, ?, 1, 'severity-one escalation', 'KEYWORD',
                        'SUCCEEDED', 1, now() - interval '700 milliseconds', now() - interval '600 milliseconds'),
                       (?, ?, ?, 1, 'incident escalation timing', 'SEMANTIC',
                        'FAILED', 0, now() - interval '700 milliseconds', now() - interval '600 milliseconds')
                """, UUID.randomUUID(), runId, subQuestionId,
                UUID.randomUUID(), runId, subQuestionId);
        dsl.execute("""
                INSERT INTO retrieval_candidate
                    (run_id, chunk_id, keyword_rank, rrf_score, rerank_score, accepted_context,
                     retrieval_sources)
                VALUES (?, ?, 1, 0.9, 0.95, true, ARRAY['keyword_search', 'rerank'])
                """, runId, chunkId);
        dsl.execute("""
                INSERT INTO evidence_item
                    (id, run_id, sub_question_id, document_id, document_version_id, chunk_id,
                     quote_text, source_start, source_end, retrieval_score, deep_read, retrieval_sources)
                VALUES (?, ?, ?, ?, ?, ?, 'within fifteen minutes', 34, 56, 0.95, true,
                        ARRAY['keyword_search', 'evidence-span'])
                """, UUID.randomUUID(), runId, subQuestionId, documentId, documentVersionId, chunkId);
        dsl.execute("""
                INSERT INTO agent_run_checkpoint (run_id, stage, state)
                VALUES (?, 'COVERAGE_JUDGE', '{"budget":{"deepReadsUsed":1,"roundsUsed":1}}'::jsonb)
                """, runId);
        dsl.execute("""
                INSERT INTO coverage_report (run_id, round_number, sufficient, report)
                VALUES (?, 1, false, '{}'::jsonb)
                """, runId);
        dsl.execute("""
                INSERT INTO rag_run_event (event_id, run_id, sequence, event_type, payload, created_at)
                VALUES
                    (?, ?, 1, 'RUN_ACCEPTED', '{}'::jsonb, now() - interval '900 milliseconds'),
                    (?, ?, 2, 'INTENT_CLASSIFIED', '{}'::jsonb, now() - interval '800 milliseconds'),
                    (?, ?, 3, 'ROUTE_SELECTED', '{"reason":"multi-intent"}'::jsonb,
                        now() - interval '700 milliseconds'),
                    (?, ?, 4, 'RERANK_COMPLETED', '{"resultCount":1}'::jsonb,
                        now() - interval '600 milliseconds'),
                    (?, ?, 5, 'DEEP_READ_COMPLETED', '{}'::jsonb, now() - interval '500 milliseconds'),
                    (?, ?, 6, 'EVIDENCE_JUDGE_STARTED', '{}'::jsonb, now() - interval '400 milliseconds'),
                    (?, ?, 7, 'EVIDENCE_JUDGE_COMPLETED', '{}'::jsonb, now() - interval '300 milliseconds'),
                    (?, ?, 8, 'GAP_QUERY_CREATED', '{}'::jsonb, now() - interval '200 milliseconds')
                """, UUID.randomUUID(), runId, UUID.randomUUID(), runId,
                UUID.randomUUID(), runId, UUID.randomUUID(), runId,
                UUID.randomUUID(), runId, UUID.randomUUID(), runId,
                UUID.randomUUID(), runId, UUID.randomUUID(), runId);

        var diagnostics = new JooqEvaluationRepository(dsl, new ObjectMapper())
                .findRagRunRetrievalDiagnostics(organizationId, runId);

        assertEquals(2L, diagnostics.get("retrievalTaskCount"));
        assertEquals(1, diagnostics.get("iterationCount"));
        assertEquals(1, diagnostics.get("evidenceCount"));
        assertEquals(1L, diagnostics.get("judgeCallCount"));
        assertEquals(1, diagnostics.get("gapQueryCount"));
        assertEquals(0, diagnostics.get("scopeLeakCount"));
        assertEquals(List.of(documentId), diagnostics.get("allDocumentIds"));
        assertEquals(List.of(documentId), diagnostics.get("deepReadDocumentIds"));
        assertEquals("LLM", diagnostics.get("routeDecisionSource"));
        assertEquals(true, diagnostics.get("routeClassifiedByModel"));
        assertTrue(diagnostics.get("routeLatencyMs") instanceof Number latency
                && latency.longValue() >= 250L && latency.longValue() < 2_000L);
        assertTrue(diagnostics.get("tool.keyword_search") instanceof Map<?, ?> keyword
                && Long.valueOf(1).equals(keyword.get("calls")));
        assertTrue(diagnostics.get("tool.semantic_search") instanceof Map<?, ?> semantic
                && Long.valueOf(1).equals(semantic.get("failed")));
        assertTrue(diagnostics.get("tool.rerank") instanceof Map<?, ?> rerank
                && Long.valueOf(1).equals(rerank.get("calls"))
                && Long.valueOf(1).equals(rerank.get("resultCount")));
        assertTrue(diagnostics.get("tool.deep_read") instanceof Map<?, ?> deepRead
                && Long.valueOf(1).equals(deepRead.get("succeeded"))
                && Long.valueOf(1).equals(deepRead.get("resultCount")));
        assertTrue(diagnostics.get("tool.evidence_judge") instanceof Map<?, ?> judge
                && Long.valueOf(1).equals(judge.get("calls"))
                && Long.valueOf(1).equals(judge.get("succeeded")));
    }

    @Test
    void claimsSchedulesOnceAndReturnsFastDeepTrendHistory() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "schedule-test");
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, 'test', 'Scheduler', 'EDITOR')
                """, userId, organizationId, "scheduler-" + userId);
        var repository = new JooqEvaluationRepository(dsl, new ObjectMapper());
        var dataset = repository.createDataset(organizationId, "scheduled checks", "");
        var fast = repository.createRun(organizationId, dataset.id(), Map.of("requestedMode", "FAST"));
        var deep = repository.createRun(organizationId, dataset.id(), Map.of("requestedMode", "DEEP"));
        var comparison = repository.createComparison(
                organizationId, userId, dataset.id(), fast.id(), deep.id(), "ANSWER");
        var dueAt = Instant.parse("2026-07-13T08:00:00Z");
        var schedule = repository.createSchedule(
                organizationId, userId, dataset.id(), "Hourly", 60, true,
                Map.of("scope", Map.of("knowledgeBaseIds", List.of(), "documentIds", List.of()),
                        "filters", List.of(), "judgeMode", "ANSWER"), dueAt);

        var claimed = repository.claimDueSchedules(dueAt.plusSeconds(1), 10);

        assertEquals(1, claimed.size());
        assertEquals(schedule.id(), claimed.getFirst().id());
        assertTrue(claimed.getFirst().nextRunAt().isAfter(dueAt.plusSeconds(1)));
        assertTrue(repository.claimDueSchedules(dueAt.plusSeconds(2), 10).isEmpty());
        repository.markScheduleTriggered(schedule.id(), comparison.id(), dueAt.plusSeconds(1));
        assertEquals(comparison.id(), repository.findSchedule(organizationId, schedule.id())
                .orElseThrow().lastComparisonId());
        dsl.execute("UPDATE evaluation_schedule SET next_run_at = ?::timestamptz WHERE id = ?",
                dueAt.toString(), schedule.id());
        assertTrue(repository.claimDueSchedules(dueAt.plusSeconds(2), 10).isEmpty());
        repository.markRunRunning(fast.id());
        repository.markRunRunning(deep.id());
        repository.completeRun(fast.id(), Map.of("requestedMode", "FAST"));
        repository.completeRun(deep.id(), Map.of("requestedMode", "DEEP"));
        assertEquals(1, repository.claimDueSchedules(dueAt.plusSeconds(2), 10).size());

        var trends = repository.findComparisonTrends(organizationId, dataset.id(), 20);
        assertEquals(1, trends.size());
        assertEquals(fast.id(), trends.getFirst().fast().id());
        assertEquals(deep.id(), trends.getFirst().deep().id());
        assertEquals("ANSWER", trends.getFirst().comparison().judgeMode());

        assertTrue(repository.deleteSchedule(organizationId, schedule.id()));
        assertTrue(repository.findSchedules(organizationId, dataset.id()).isEmpty());
    }

    @Test
    void notificationWaitsForBothRunsAndPersistsFencedRetryHistory() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "notification-test");
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, 'test', 'Notifier', 'EDITOR')
                """, userId, organizationId, "notifier-" + userId);
        var repository = new JooqEvaluationRepository(dsl, new ObjectMapper());
        var dataset = repository.createDataset(organizationId, "Notification checks", "");
        var fast = repository.createRun(organizationId, dataset.id(), Map.of("requestedMode", "FAST"));
        var deep = repository.createRun(organizationId, dataset.id(), Map.of("requestedMode", "DEEP"));
        var comparison = repository.createComparison(
                organizationId, userId, dataset.id(), fast.id(), deep.id(), "NONE");
        var now = Instant.parse("2026-07-13T11:00:00Z");
        var schedule = repository.createSchedule(
                organizationId, userId, dataset.id(), "Signed nightly", 1440, true,
                Map.of("scope", Map.of("knowledgeBaseIds", List.of(), "documentIds", List.of()),
                        "filters", List.of(), "judgeMode", "NONE"),
                true, "https://events.example.com/rag", "encrypted-secret", now.plusSeconds(60));

        repository.markScheduleTriggered(schedule.id(), comparison.id(), now);

        assertTrue(repository.claimReadyNotifications(now, now.minusSeconds(120), 10).isEmpty());
        repository.markRunRunning(fast.id());
        repository.markRunRunning(deep.id());
        repository.completeRun(fast.id(), Map.of("recallAt10", 0.8));
        assertTrue(repository.claimReadyNotifications(now, now.minusSeconds(120), 10).isEmpty());
        repository.completeRun(deep.id(), Map.of("recallAt10", 0.9));

        var first = repository.claimReadyNotifications(now, now.minusSeconds(120), 10).getFirst();
        assertEquals(1, first.attempt());
        assertEquals("DELIVERING", first.status());
        assertEquals("encrypted-secret", first.signingSecretCiphertext());
        assertEquals(EvaluationRunStatus.COMPLETED, first.fastRun().status());
        repository.completeNotification(first.id(), 99, 204, "wrong lease", now);
        assertEquals("DELIVERING", repository.findNotification(organizationId, first.id()).orElseThrow().status());

        repository.failNotification(
                first.id(), first.attempt(), true, now.plusSeconds(30), 503, "later", "HTTP 503", now);
        assertTrue(repository.claimReadyNotifications(
                now.plusSeconds(29), now.minusSeconds(120), 10).isEmpty());
        var second = repository.claimReadyNotifications(
                now.plusSeconds(31), now.minusSeconds(120), 10).getFirst();
        assertEquals(2, second.attempt());
        repository.completeNotification(second.id(), second.attempt(), 204, "accepted", now.plusSeconds(31));

        var delivered = repository.findNotifications(organizationId, schedule.id(), 20).getFirst();
        assertEquals("SUCCEEDED", delivered.status());
        assertEquals(204, delivered.responseStatus());
        assertEquals("SUCCEEDED", repository.findSchedule(organizationId, schedule.id())
                .orElseThrow().lastNotification().status());
    }

    private RankedDocument insertRankedDocument(UUID organizationId, UUID knowledgeBaseId, String title) {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var chunkId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO document
                    (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                """, documentId, knowledgeBaseId, organizationId, title);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, content_hash, status, published_at)
                VALUES (?, ?, 1, ?, repeat('a', 64), 'PUBLISHED', now())
                """, versionId, documentId, title + ".md");
        dsl.execute("UPDATE document SET current_version_id = ? WHERE id = ?", versionId, documentId);
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, chunk_type, order_index, chunk_text, embedding_text,
                     estimated_tokens, chunk_hash, chunk_policy_version)
                VALUES (?, ?, 'CHILD', 0, ?, ?, 4, repeat('b', 64), 'test-v1')
                """, chunkId, versionId, title + " evidence", title + " evidence");
        return new RankedDocument(documentId, versionId, chunkId);
    }

    private void insertRankedCandidate(
            UUID runId,
            UUID goalId,
            int goalOrder,
            String phase,
            RankedDocument document,
            int rank,
            double score,
            String source
    ) {
        insertRankedCandidateWithRanks(runId, goalId, goalOrder, phase, document,
                rank, score, rank, score, source);
    }

    private void insertRankedCandidateWithRanks(
            UUID runId,
            UUID goalId,
            int goalOrder,
            String phase,
            RankedDocument document,
            Integer rrfRank,
            Double rrfScore,
            Integer rerankRank,
            Double rerankScore,
            String source
    ) {
        int bestRawRank = rrfRank == null ? rerankRank : rrfRank;
        double bestRawScore = rrfScore == null ? rerankScore : rrfScore;
        dsl.execute("""
                INSERT INTO agent_goal_ranked_candidate
                    (run_id, goal_id, goal_order, phase, chunk_id, document_id, document_version_id,
                     best_raw_rank, best_raw_score, rrf_rank, rrf_score, rerank_rank, rerank_score,
                     rerank_fallback, selected_for_parent, retrieval_sources)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, false, ARRAY[?]::text[])
                """, runId, goalId, goalOrder, phase, document.chunkId(), document.documentId(),
                document.versionId(), bestRawRank, bestRawScore, rrfRank, rrfScore, rerankRank, rerankScore, source);
    }

    private void insertV7RankingRun(UUID organizationId, UUID knowledgeBaseId, UUID runId) {
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "v7-ranking-test");
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, 'V7 ranking', '', '{}'::jsonb, now(), now())
                """, knowledgeBaseId, organizationId);
        dsl.execute("""
                INSERT INTO rag_run
                    (id, organization_id, requested_mode, selected_mode, query_text, status,
                     pipeline_version, prompt_version, started_at)
                VALUES (?, ?, 'DEEP', 'DEEP', 'v7 ranking', 'RUNNING',
                        'agentic-rag-v7', 'prompt-v7', now())
                """, runId, organizationId);
    }

    private UUID insertEvidence(
            UUID runId,
            UUID goalId,
            UUID requirementId,
            RankedDocument document,
            String quote
    ) {
        var evidenceId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO evidence_item
                    (id, run_id, sub_question_id, document_id, document_version_id, chunk_id,
                     quote_text, source_start, source_end, retrieval_score, deep_read, retrieval_sources)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, 0.90, true, ARRAY['deep-read'])
                """, evidenceId, runId, goalId, document.documentId(), document.versionId(), document.chunkId(),
                quote, quote.length());
        dsl.execute("""
                INSERT INTO evidence_requirement (evidence_id, requirement_id, accepted_phase, target_effect)
                VALUES (?, ?, 'PRIMARY', 'COMPLETE')
                """, evidenceId, requirementId);
        return evidenceId;
    }

    private record RankedDocument(UUID documentId, UUID versionId, UUID chunkId) {
    }
}
