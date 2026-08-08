package com.yanyue.rag.worker.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanyue.rag.domain.model.EmbeddingModelReference;
import com.yanyue.rag.domain.port.EmbeddingModelPort;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IndexRebuildProcessorTest {
    private static PostgreSQLContainer postgres;
    private static DSLContext dsl;
    private static TransactionTemplate transactions;

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
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearBusinessData() {
        dsl.execute("TRUNCATE TABLE organization CASCADE");
    }

    @AfterEach
    void removeFaultTriggers() {
        removeActivationFailure();
    }

    @Test
    void transientEmbeddingFailureResumesTheSameBuildingGeneration() {
        var fixture = fixture();
        var embeddings = new FaultInjectingEmbeddings(1, null);
        var processor = new IndexRebuildProcessor(dsl, transactions, embeddings, 1);

        processor.processNext();

        assertThat(jobValue(fixture.jobId(), "status", String.class)).isEqualTo("QUEUED");
        assertThat(jobValue(fixture.jobId(), "attempt", Integer.class)).isEqualTo(1);
        assertThat(generationStatus(fixture.oldGenerationId())).isEqualTo("ACTIVE");
        assertThat(generationStatus(fixture.newGenerationId())).isEqualTo("BUILDING");
        assertThat(jobValue(fixture.jobId(), "error_message", String.class))
                .contains("injected embedding failure");

        makeRetryDue(fixture.jobId());
        processor.processNext();

        assertThat(jobValue(fixture.jobId(), "status", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jobValue(fixture.jobId(), "attempt", Integer.class)).isEqualTo(2);
        assertThat(jobValue(fixture.jobId(), "completed_chunks", Integer.class)).isEqualTo(1);
        assertThat(generationStatus(fixture.oldGenerationId())).isEqualTo("RETIRED");
        assertThat(generationStatus(fixture.newGenerationId())).isEqualTo("ACTIVE");
        assertThat(vectorCount(fixture.newGenerationId())).isEqualTo(1);
        assertThat(embeddings.calls()).isEqualTo(2);
    }

    @Test
    void rebuildBecomesTerminalOnlyAfterTheConfiguredAttemptLimit() {
        var fixture = fixture();
        var embeddings = new FaultInjectingEmbeddings(3, null);
        var processor = new IndexRebuildProcessor(dsl, transactions, embeddings, 1);

        processor.processNext();
        makeRetryDue(fixture.jobId());
        processor.processNext();
        makeRetryDue(fixture.jobId());
        processor.processNext();

        assertThat(jobValue(fixture.jobId(), "status", String.class)).isEqualTo("FAILED");
        assertThat(jobValue(fixture.jobId(), "attempt", Integer.class)).isEqualTo(3);
        assertThat(generationStatus(fixture.oldGenerationId())).isEqualTo("ACTIVE");
        assertThat(generationStatus(fixture.newGenerationId())).isEqualTo("FAILED");
        assertThat(embeddings.calls()).isEqualTo(3);
    }

    @Test
    void failedGenerationSwitchKeepsThePreviousGenerationActiveAndReusesBuiltVectors() {
        var fixture = fixture();
        var embeddings = new FaultInjectingEmbeddings(0, null);
        var processor = new IndexRebuildProcessor(dsl, transactions, embeddings, 1);
        installActivationFailure(fixture.newGenerationId());

        processor.processNext();

        assertThat(jobValue(fixture.jobId(), "status", String.class)).isEqualTo("QUEUED");
        assertThat(generationStatus(fixture.oldGenerationId())).isEqualTo("ACTIVE");
        assertThat(generationStatus(fixture.newGenerationId())).isEqualTo("BUILDING");
        assertThat(vectorCount(fixture.newGenerationId())).isEqualTo(1);

        removeActivationFailure();
        makeRetryDue(fixture.jobId());
        processor.processNext();

        assertThat(jobValue(fixture.jobId(), "status", String.class)).isEqualTo("SUCCEEDED");
        assertThat(generationStatus(fixture.oldGenerationId())).isEqualTo("RETIRED");
        assertThat(generationStatus(fixture.newGenerationId())).isEqualTo("ACTIVE");
        assertThat(vectorCount(fixture.newGenerationId())).isEqualTo(1);
        assertThat(embeddings.calls()).isEqualTo(1);
    }

    @Test
    void rebuildIncludesDocumentsPublishedWhileItsBatchesAreRunning() {
        var fixture = fixture();
        var added = new AtomicBoolean();
        var embeddings = new FaultInjectingEmbeddings(0, () -> {
            if (added.compareAndSet(false, true)) {
                insertPublishedDocument(fixture.organizationId(), fixture.knowledgeBaseId(),
                        "late-policy-" + fixture.jobId());
            }
        });
        var processor = new IndexRebuildProcessor(dsl, transactions, embeddings, 1);

        processor.processNext();

        assertThat(jobValue(fixture.jobId(), "status", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jobValue(fixture.jobId(), "total_chunks", Integer.class)).isEqualTo(2);
        assertThat(jobValue(fixture.jobId(), "completed_chunks", Integer.class)).isEqualTo(2);
        assertThat(generationStatus(fixture.newGenerationId())).isEqualTo("ACTIVE");
        assertThat(vectorCount(fixture.newGenerationId())).isEqualTo(2);
        assertThat(embeddings.calls()).isEqualTo(2);
    }

    @Test
    void activeIngestionDefersGenerationActivationWithoutDiscardingBuiltVectors() {
        var fixture = fixture();
        var embeddings = new FaultInjectingEmbeddings(0, () -> insertPendingIngestion(fixture));
        var processor = new IndexRebuildProcessor(dsl, transactions, embeddings, 1);

        processor.processNext();

        assertThat(jobValue(fixture.jobId(), "status", String.class)).isEqualTo("QUEUED");
        assertThat(generationStatus(fixture.oldGenerationId())).isEqualTo("ACTIVE");
        assertThat(generationStatus(fixture.newGenerationId())).isEqualTo("BUILDING");
        assertThat(vectorCount(fixture.newGenerationId())).isEqualTo(1);

        dsl.execute("DELETE FROM ingestion_job WHERE knowledge_base_id = ?", fixture.knowledgeBaseId());
        makeRetryDue(fixture.jobId());
        processor.processNext();

        assertThat(jobValue(fixture.jobId(), "status", String.class)).isEqualTo("SUCCEEDED");
        assertThat(generationStatus(fixture.newGenerationId())).isEqualTo("ACTIVE");
        assertThat(embeddings.calls()).isEqualTo(1);
    }

    @Test
    void staleRunningRebuildIsRecoveredOrTerminalizedAccordingToItsAttempt() {
        var recoverable = fixture();
        dsl.execute("""
                UPDATE index_rebuild_job
                SET status = 'RUNNING', attempt = 1, updated_at = now() - interval '10 seconds'
                WHERE id = ?
                """, recoverable.jobId());
        var exhausted = fixture();
        dsl.execute("""
                UPDATE index_rebuild_job
                SET status = 'RUNNING', attempt = max_attempts, updated_at = now() - interval '10 seconds'
                WHERE id = ?
                """, exhausted.jobId());

        new StaleIndexRebuildRecovery(dsl, transactions, 1).recover();

        assertThat(jobValue(recoverable.jobId(), "status", String.class)).isEqualTo("QUEUED");
        assertThat(generationStatus(recoverable.newGenerationId())).isEqualTo("BUILDING");
        assertThat(jobValue(exhausted.jobId(), "status", String.class)).isEqualTo("FAILED");
        assertThat(generationStatus(exhausted.newGenerationId())).isEqualTo("FAILED");
        assertThat(generationStatus(exhausted.oldGenerationId())).isEqualTo("ACTIVE");
    }

    private Fixture fixture() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var oldGenerationId = UUID.randomUUID();
        var newGenerationId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "org-" + organizationId);
        dsl.execute("""
                INSERT INTO knowledge_base (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, ?, '', '{"version":"parent-child-v1"}'::jsonb, now(), now())
                """, knowledgeBaseId, organizationId, "kb-" + knowledgeBaseId);
        dsl.execute("""
                INSERT INTO index_generation
                    (id, knowledge_base_id, generation_number, status, embedding_model_id,
                     embedding_model_version, embedding_dimension, chunk_policy_version, activated_at)
                VALUES (?, ?, 1, 'ACTIVE', 'old-model', 'v1', 3, 'parent-child-v1', now())
                """, oldGenerationId, knowledgeBaseId);
        dsl.execute("""
                INSERT INTO index_generation
                    (id, knowledge_base_id, generation_number, status, embedding_model_id,
                     embedding_model_version, embedding_dimension, chunk_policy_version)
                VALUES (?, ?, 2, 'BUILDING', 'new-model', 'v2', 3, 'parent-child-v1')
                """, newGenerationId, knowledgeBaseId);
        insertPublishedDocument(organizationId, knowledgeBaseId, "base-policy-" + jobId);
        dsl.execute("""
                INSERT INTO index_rebuild_job
                    (id, organization_id, knowledge_base_id, index_generation_id, status, total_chunks)
                VALUES (?, ?, ?, ?, 'QUEUED', 1)
                """, jobId, organizationId, knowledgeBaseId, newGenerationId);
        return new Fixture(organizationId, knowledgeBaseId, oldGenerationId, newGenerationId, jobId);
    }

    private void insertPublishedDocument(UUID organizationId, UUID knowledgeBaseId, String name) {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var parentId = UUID.randomUUID();
        var childId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO document (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                """, documentId, knowledgeBaseId, organizationId, name);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, source_type, content_hash, status,
                     published_at, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'PDF', repeat('a', 64), 'PUBLISHED', now(), now(), now())
                """, versionId, documentId, name + ".pdf");
        dsl.execute("UPDATE document SET current_version_id = ? WHERE id = ?", versionId, documentId);
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, chunk_type, order_index, chunk_text, embedding_text,
                     estimated_tokens, chunk_hash, chunk_policy_version)
                VALUES (?, ?, 'PARENT', 0, ?, ?, 8, ?, 'parent-child-v1')
                """, parentId, versionId, name + " parent", name + " parent", hash(name + "-parent"));
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, parent_chunk_id, chunk_type, order_index, chunk_text,
                     embedding_text, estimated_tokens, chunk_hash, chunk_policy_version)
                VALUES (?, ?, ?, 'CHILD', 0, ?, ?, 8, ?, 'parent-child-v1')
                """, childId, versionId, parentId, name + " child", name + " child", hash(name + "-child"));
    }

    private void insertPendingIngestion(Fixture fixture) {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO document (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'pending', 'ACTIVE', now(), now())
                """, documentId, fixture.knowledgeBaseId(), fixture.organizationId());
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, content_hash, status, created_at, updated_at)
                VALUES (?, ?, 1, 'pending.pdf', repeat('0', 64), 'PROCESSING', now(), now())
                """, versionId, documentId);
        dsl.execute("""
                INSERT INTO ingestion_job
                    (organization_id, knowledge_base_id, document_id, document_version_id, status, idempotency_key)
                VALUES (?, ?, ?, ?, 'PENDING', ?)
                """, fixture.organizationId(), fixture.knowledgeBaseId(), documentId, versionId,
                "pending-" + versionId);
    }

    private void installActivationFailure(UUID generationId) {
        dsl.execute("""
                CREATE OR REPLACE FUNCTION fail_index_generation_activation() RETURNS trigger AS $$
                BEGIN
                    IF NEW.id = '%s'::uuid AND NEW.status = 'ACTIVE' THEN
                        RAISE EXCEPTION 'injected index activation failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(generationId));
        dsl.execute("""
                CREATE TRIGGER fail_index_generation_activation
                BEFORE UPDATE OF status ON index_generation
                FOR EACH ROW EXECUTE FUNCTION fail_index_generation_activation()
                """);
    }

    private void removeActivationFailure() {
        dsl.execute("DROP TRIGGER IF EXISTS fail_index_generation_activation ON index_generation");
        dsl.execute("DROP FUNCTION IF EXISTS fail_index_generation_activation()");
    }

    private void makeRetryDue(UUID jobId) {
        dsl.execute("UPDATE index_rebuild_job SET next_attempt_at = now() WHERE id = ?", jobId);
    }

    private String generationStatus(UUID generationId) {
        return dsl.fetchOne("SELECT status FROM index_generation WHERE id = ?", generationId).get(0, String.class);
    }

    private int vectorCount(UUID generationId) {
        return dsl.fetchOne("SELECT count(*) FROM chunk_embedding WHERE index_generation_id = ?", generationId)
                .get(0, Integer.class);
    }

    private <T> T jobValue(UUID jobId, String column, Class<T> type) {
        return dsl.fetchOne("SELECT " + column + " FROM index_rebuild_job WHERE id = ?", jobId).get(0, type);
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            UUID organizationId,
            UUID knowledgeBaseId,
            UUID oldGenerationId,
            UUID newGenerationId,
            UUID jobId
    ) { }

    private static final class FaultInjectingEmbeddings implements EmbeddingModelPort {
        private final AtomicInteger failures;
        private final AtomicInteger calls = new AtomicInteger();
        private final Runnable firstCall;

        private FaultInjectingEmbeddings(int failures, Runnable firstCall) {
            this.failures = new AtomicInteger(failures);
            this.firstCall = firstCall;
        }

        @Override
        public List<List<Float>> embed(EmbeddingModelReference model, List<String> texts) {
            var call = calls.incrementAndGet();
            if (call == 1 && firstCall != null) firstCall.run();
            if (failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("injected embedding failure");
            }
            return texts.stream().map(ignored -> List.of(0.1f, 0.2f, 0.3f)).toList();
        }

        private int calls() {
            return calls.get();
        }
    }
}
