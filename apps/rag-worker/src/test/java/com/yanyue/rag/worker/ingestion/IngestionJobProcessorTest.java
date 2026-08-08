package com.yanyue.rag.worker.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.contract.parser.BlockType;
import com.yanyue.rag.contract.parser.NormalizedBlock;
import com.yanyue.rag.contract.parser.NormalizedDocument;
import com.yanyue.rag.contract.parser.ParseQualityIssue;
import com.yanyue.rag.contract.parser.ParseQualityReport;
import com.yanyue.rag.contract.parser.ParseQualityStatus;
import com.yanyue.rag.domain.knowledge.ChunkPolicy;
import com.yanyue.rag.domain.model.EmbeddingModelReference;
import com.yanyue.rag.domain.port.EmbeddingModelPort;
import com.yanyue.rag.worker.parser.DocumentParsingService;
import com.yanyue.rag.worker.storage.StoredDocumentReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IngestionJobProcessorTest {
    private static final byte[] SOURCE = "versioned source document".getBytes(java.nio.charset.StandardCharsets.UTF_8);

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

    @Test
    void duplicateDeliveryDoesNotRepeatCompletedWorkOrCreateDuplicateIndexRows() {
        var fixture = fixture();
        var parser = parser(fixture);
        var embeddings = new FaultInjectingEmbeddings(0);
        var processor = processor(parser, embeddings);

        processor.process(fixture.jobId());
        processor.process(fixture.jobId());

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(value("SELECT attempt FROM ingestion_job WHERE id = ?", fixture.jobId(), Integer.class))
                .isEqualTo(1);
        assertThat(value("SELECT count(*) FROM chunk WHERE document_version_id = ?", fixture.newVersionId(), Integer.class))
                .isEqualTo(2);
        assertThat(value("""
                SELECT count(*) FROM chunk_embedding ce
                JOIN chunk c ON c.id = ce.chunk_id
                WHERE c.document_version_id = ?
                """, fixture.newVersionId(), Integer.class)).isEqualTo(1);
        assertThat(value("SELECT max(attempt) FROM ingestion_job_stage WHERE job_id = ?", fixture.jobId(), Integer.class))
                .isEqualTo(1);
        assertPublished(fixture);
        verify(parser, times(1)).parse(
                fixture.objectKey(), "policy.pdf", "application/pdf", SOURCE, "AUTO", Map.of());
        assertThat(embeddings.calls()).isEqualTo(1);
    }

    @Test
    void matchingParserFingerprintReusesTheNormalizedArtifact() {
        var fixture = fixture();
        var parser = parser(fixture);
        installReusableArtifact(fixture, "2", "AUTO", DocumentParsingService.effectiveOptions(Map.of()));

        processor(parser, new FaultInjectingEmbeddings(0)).process(fixture.jobId());

        verify(parser, never()).parse(
                fixture.objectKey(), "policy.pdf", "application/pdf", SOURCE, "AUTO", Map.of());
        assertThat(value("""
                SELECT (metrics ->> 'normalizedArtifactReused')::boolean
                FROM ingestion_job_stage WHERE job_id = ? AND stage = 'PARSE'
                """, fixture.jobId(), Boolean.class)).isTrue();
    }

    @Test
    void changedParserOptionsInvalidateTheNormalizedArtifact() {
        var fixture = fixture();
        var parser = parser(fixture);
        installReusableArtifact(fixture, "2", "AUTO", DocumentParsingService.effectiveOptions(Map.of()));
        var options = Map.<String, Object>of("ocr", "force");
        dsl.execute("UPDATE ingestion_job SET parser_options = ?::jsonb WHERE id = ?", json(options), fixture.jobId());
        when(parser.parse(fixture.objectKey(), "policy.pdf", "application/pdf", SOURCE, "AUTO", options))
                .thenReturn(normalized(ParseQualityReport.legacyPass(), "2", Map.of()));

        processor(parser, new FaultInjectingEmbeddings(0)).process(fixture.jobId());

        verify(parser).parse(fixture.objectKey(), "policy.pdf", "application/pdf", SOURCE, "AUTO", options);
        assertThat(value("""
                SELECT (metrics ->> 'normalizedArtifactReused')::boolean
                FROM ingestion_job_stage WHERE job_id = ? AND stage = 'PARSE'
                """, fixture.jobId(), Boolean.class)).isFalse();
    }

    @Test
    void embeddingFailureRetriesOnlyTheFailedStageAndPublishesOnce() {
        var fixture = fixture();
        var parser = parser(fixture);
        var embeddings = new FaultInjectingEmbeddings(1);
        var processor = processor(parser, embeddings);

        processor.process(fixture.jobId());

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("PENDING");
        assertThat(stageStatus(fixture.jobId(), "PARSE")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.jobId(), "NORMALIZE")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.jobId(), "CHUNK")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.jobId(), "EMBED")).isEqualTo("FAILED");
        assertThat(stageStatus(fixture.jobId(), "PUBLISH")).isEqualTo("PENDING");
        assertThat(value("SELECT current_version_id FROM document WHERE id = ?", fixture.documentId(), UUID.class))
                .isEqualTo(fixture.oldVersionId());
        assertThat(value("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ingestion.retry'",
                fixture.jobId(), Integer.class)).isEqualTo(1);

        processor.process(fixture.jobId());

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(value("SELECT attempt FROM ingestion_job WHERE id = ?", fixture.jobId(), Integer.class))
                .isEqualTo(2);
        assertThat(stageAttempt(fixture.jobId(), "PARSE")).isEqualTo(1);
        assertThat(stageAttempt(fixture.jobId(), "NORMALIZE")).isEqualTo(1);
        assertThat(stageAttempt(fixture.jobId(), "CHUNK")).isEqualTo(1);
        assertThat(stageAttempt(fixture.jobId(), "EMBED")).isEqualTo(2);
        assertThat(stageAttempt(fixture.jobId(), "PUBLISH")).isEqualTo(1);
        assertPublished(fixture);
        verify(parser, times(1)).parse(
                fixture.objectKey(), "policy.pdf", "application/pdf", SOURCE, "AUTO", Map.of());
        assertThat(embeddings.calls()).isEqualTo(2);
    }

    @Test
    void objectStorageFailureRetriesFromParseWithoutPublishingUnavailableContent() {
        var fixture = fixture();
        var parser = parser(fixture);
        var embeddings = new FaultInjectingEmbeddings(0);
        var reader = mock(StoredDocumentReader.class);
        when(reader.read(fixture.objectKey()))
                .thenThrow(new IllegalStateException("injected MinIO outage"))
                .thenReturn(SOURCE);
        var processor = processor(parser, embeddings, reader);

        processor.process(fixture.jobId());

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("PENDING");
        assertThat(stageStatus(fixture.jobId(), "PARSE")).isEqualTo("FAILED");
        assertThat(stageStatus(fixture.jobId(), "NORMALIZE")).isEqualTo("PENDING");
        assertThat(value("SELECT current_version_id FROM document WHERE id = ?", fixture.documentId(), UUID.class))
                .isEqualTo(fixture.oldVersionId());
        assertThat(value("SELECT status FROM document_version WHERE id = ?", fixture.newVersionId(), String.class))
                .isEqualTo("PROCESSING");

        processor.process(fixture.jobId());

        assertPublished(fixture);
        assertThat(stageAttempt(fixture.jobId(), "PARSE")).isEqualTo(2);
        assertThat(stageAttempt(fixture.jobId(), "NORMALIZE")).isEqualTo(1);
        verify(reader, times(2)).read(fixture.objectKey());
        verify(parser, times(1)).parse(
                fixture.objectKey(), "policy.pdf", "application/pdf", SOURCE, "AUTO", Map.of());
        assertThat(embeddings.calls()).isEqualTo(1);
    }

    @Test
    void failedPointerSwitchRollsBackTheEntireVersionPublication() {
        var fixture = fixture();
        var parser = parser(fixture);
        var embeddings = new FaultInjectingEmbeddings(0);
        var processor = processor(parser, embeddings);
        installPointerSwitchFailure(fixture.newVersionId());

        processor.process(fixture.jobId());

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("PENDING");
        assertThat(stageStatus(fixture.jobId(), "PUBLISH")).isEqualTo("FAILED");
        assertThat(value("SELECT status FROM document_version WHERE id = ?", fixture.oldVersionId(), String.class))
                .isEqualTo("PUBLISHED");
        assertThat(value("SELECT status FROM document_version WHERE id = ?", fixture.newVersionId(), String.class))
                .isEqualTo("READY");
        assertThat(value("SELECT current_version_id FROM document WHERE id = ?", fixture.documentId(), UUID.class))
                .isEqualTo(fixture.oldVersionId());

        removePointerSwitchFailure();
        processor.process(fixture.jobId());

        assertPublished(fixture);
        assertThat(stageAttempt(fixture.jobId(), "PARSE")).isEqualTo(1);
        assertThat(stageAttempt(fixture.jobId(), "EMBED")).isEqualTo(1);
        assertThat(stageAttempt(fixture.jobId(), "PUBLISH")).isEqualTo(2);
        verify(parser, times(1)).parse(
                fixture.objectKey(), "policy.pdf", "application/pdf", SOURCE, "AUTO", Map.of());
        assertThat(embeddings.calls()).isEqualTo(1);
    }

    @Test
    void warningQualityGateBuildsPreviewButWaitsForApprovalBeforeEmbeddingAndPublishing() {
        var fixture = fixture();
        var report = new ParseQualityReport(ParseQualityStatus.WARNING, 72,
                List.of(new ParseQualityIssue("SPARSE_PAGE", ParseQualityStatus.WARNING,
                        "One page contains unusually little extractable text", List.of("block-1"))),
                Map.of("pageCoverage", 0.8));
        var parser = parser(fixture, report);
        var embeddings = new FaultInjectingEmbeddings(0);
        var processor = processor(parser, embeddings);

        processor.process(fixture.jobId());

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("AWAITING_REVIEW");
        assertThat(value("SELECT status FROM document_version WHERE id = ?", fixture.newVersionId(), String.class))
                .isEqualTo("REVIEW_REQUIRED");
        assertThat(stageStatus(fixture.jobId(), "QUALITY")).isEqualTo("REVIEW_REQUIRED");
        assertThat(stageStatus(fixture.jobId(), "CHUNK")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.jobId(), "EMBED")).isEqualTo("PENDING");
        assertThat(value("SELECT count(*) FROM chunk WHERE document_version_id = ?",
                fixture.newVersionId(), Integer.class)).isEqualTo(2);
        assertThat(value("SELECT current_version_id FROM document WHERE id = ?",
                fixture.documentId(), UUID.class)).isEqualTo(fixture.oldVersionId());
        assertThat(embeddings.calls()).isZero();

        dsl.execute("""
                UPDATE ingestion_job
                SET status = 'PENDING', attempt = 0, quality_approved_at = now(), error_message = NULL
                WHERE id = ?
                """, fixture.jobId());
        dsl.execute("UPDATE document_version SET status = 'PROCESSING' WHERE id = ?", fixture.newVersionId());
        dsl.execute("""
                UPDATE ingestion_job_stage SET status = 'PENDING', completed_at = NULL
                WHERE job_id = ? AND stage = 'QUALITY'
                """, fixture.jobId());

        processor.process(fixture.jobId());

        assertPublished(fixture);
        assertThat(embeddings.calls()).isEqualTo(1);
        verify(parser, times(1)).parse(
                fixture.objectKey(), "policy.pdf", "application/pdf", SOURCE, "AUTO", Map.of());
    }

    @Test
    void failedQualityGateStopsImmediatelyAndKeepsThePublishedVersionActive() {
        var fixture = fixture();
        var report = new ParseQualityReport(ParseQualityStatus.FAIL, 20,
                List.of(new ParseQualityIssue("EMPTY_CONTENT", ParseQualityStatus.FAIL,
                        "No reliable document body was extracted", List.of())),
                Map.of("normalizedCharacters", 42));
        var parser = parser(fixture, report);
        var embeddings = new FaultInjectingEmbeddings(0);

        processor(parser, embeddings).process(fixture.jobId());

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("FAILED");
        assertThat(stageStatus(fixture.jobId(), "QUALITY")).isEqualTo("FAILED");
        assertThat(stageStatus(fixture.jobId(), "CHUNK")).isEqualTo("PENDING");
        assertThat(value("SELECT status FROM document_version WHERE id = ?", fixture.newVersionId(), String.class))
                .isEqualTo("FAILED");
        assertThat(value("SELECT current_version_id FROM document WHERE id = ?",
                fixture.documentId(), UUID.class)).isEqualTo(fixture.oldVersionId());
        assertThat(value("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ingestion.retry'",
                fixture.jobId(), Integer.class)).isZero();
        assertThat(value("SELECT count(*) FROM document_block WHERE document_version_id = ?",
                fixture.newVersionId(), Integer.class)).isEqualTo(1);
        assertThat(embeddings.calls()).isZero();
    }

    @Test
    void recoveredJobFencesTheOldWorkerBeforeItCanPersistOrPublish() throws Exception {
        var fixture = fixture();
        var parser = parser(fixture);
        var enteredRead = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        var blockedReader = mock(StoredDocumentReader.class);
        when(blockedReader.read(fixture.objectKey())).thenAnswer(ignored -> {
            enteredRead.countDown();
            if (!releaseRead.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test reader was not released");
            }
            return SOURCE;
        });
        var oldProcessor = processor(parser, new FaultInjectingEmbeddings(0), blockedReader, 3_600);

        var oldRun = CompletableFuture.runAsync(() -> oldProcessor.process(fixture.jobId()));
        assertThat(enteredRead.await(10, TimeUnit.SECONDS)).isTrue();
        dsl.execute("UPDATE ingestion_job SET heartbeat_at = now() - interval '10 minutes' WHERE id = ?",
                fixture.jobId());

        new StaleJobRecovery(dsl, transactions, 1).recover();

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("PENDING");
        releaseRead.countDown();
        oldRun.get(10, TimeUnit.SECONDS);

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("PENDING");
        assertThat(value("SELECT count(*) FROM ingestion_artifact WHERE job_id = ?", fixture.jobId(), Integer.class))
                .isZero();
        assertThat(value("SELECT current_version_id FROM document WHERE id = ?", fixture.documentId(), UUID.class))
                .isEqualTo(fixture.oldVersionId());
        assertThat(value("""
                SELECT count(*) FROM outbox_event
                WHERE aggregate_id = ? AND event_type = 'ingestion.recovered'
                """, fixture.jobId(), Integer.class)).isEqualTo(1);

        processor(parser, new FaultInjectingEmbeddings(0)).process(fixture.jobId());

        assertPublished(fixture);
        assertThat(value("SELECT attempt FROM ingestion_job WHERE id = ?", fixture.jobId(), Integer.class))
                .isEqualTo(2);
    }

    @Test
    void staleJobAtRetryLimitFailsTheJobAndDocumentVersion() {
        var fixture = fixture();
        dsl.execute("""
                UPDATE ingestion_job
                SET status = 'RUNNING', current_stage = 'PARSE', attempt = max_attempts,
                    started_at = now() - interval '10 minutes', heartbeat_at = now() - interval '10 minutes'
                WHERE id = ?
                """, fixture.jobId());
        dsl.execute("""
                UPDATE ingestion_job_stage
                SET status = 'RUNNING', attempt = 3, started_at = now() - interval '10 minutes'
                WHERE job_id = ? AND stage = 'PARSE'
                """, fixture.jobId());

        new StaleJobRecovery(dsl, transactions, 1).recover();

        assertThat(value("SELECT status FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("FAILED");
        assertThat(value("SELECT error_code FROM ingestion_job WHERE id = ?", fixture.jobId(), String.class))
                .isEqualTo("STALE_WORKER");
        assertThat(value("SELECT completed_at IS NOT NULL FROM ingestion_job WHERE id = ?",
                fixture.jobId(), Boolean.class)).isTrue();
        assertThat(stageStatus(fixture.jobId(), "PARSE")).isEqualTo("FAILED");
        assertThat(value("SELECT status FROM document_version WHERE id = ?", fixture.newVersionId(), String.class))
                .isEqualTo("FAILED");
        assertThat(value("""
                SELECT count(*) FROM outbox_event
                WHERE aggregate_id = ? AND event_type = 'ingestion.recovered'
                """, fixture.jobId(), Integer.class)).isZero();
        assertThat(value("SELECT current_version_id FROM document WHERE id = ?", fixture.documentId(), UUID.class))
                .isEqualTo(fixture.oldVersionId());
    }

    private IngestionJobProcessor processor(DocumentParsingService parser, EmbeddingModelPort embeddings) {
        var reader = mock(StoredDocumentReader.class);
        when(reader.read(org.mockito.ArgumentMatchers.anyString())).thenReturn(SOURCE);
        return processor(parser, embeddings, reader);
    }

    private IngestionJobProcessor processor(
            DocumentParsingService parser,
            EmbeddingModelPort embeddings,
            StoredDocumentReader reader
    ) {
        return processor(parser, embeddings, reader, 30);
    }

    private IngestionJobProcessor processor(
            DocumentParsingService parser,
            EmbeddingModelPort embeddings,
            StoredDocumentReader reader,
            long heartbeatIntervalSeconds
    ) {
        return new IngestionJobProcessor(dsl, new ObjectMapper().findAndRegisterModules(), transactions,
                reader, parser, embeddings, 32, heartbeatIntervalSeconds, true, RagTelemetry.noop());
    }

    private DocumentParsingService parser(Fixture fixture) {
        return parser(fixture, ParseQualityReport.legacyPass());
    }

    private DocumentParsingService parser(Fixture fixture, ParseQualityReport quality) {
        var parser = mock(DocumentParsingService.class);
        when(parser.identity()).thenReturn(new DocumentParsingService.ParserIdentity(
                "fault-injection-parser", "2", "2.0"));
        when(parser.parse(fixture.objectKey(), "policy.pdf", "application/pdf", SOURCE, "AUTO", Map.of()))
                .thenReturn(normalized(quality, "2", Map.of()));
        return parser;
    }

    private NormalizedDocument normalized(
            ParseQualityReport quality,
            String parserVersion,
            Map<String, Object> metadata
    ) {
        return new NormalizedDocument(
                "2.0", "fault-injection-parser", parserVersion, "Policy", "policy.pdf", null,
                Instant.parse("2026-01-01T00:00:00Z"), metadata,
                "The active policy requires review before publication.", quality,
                List.of(new NormalizedBlock(
                        "block-1", BlockType.PARAGRAPH,
                        "The active policy requires review before publication.", 0, 1,
                        List.of("Publication policy"), null, 0, 53, "UTF16_CODE_UNIT", Map.of())));
    }

    private void installReusableArtifact(
            Fixture fixture,
            String parserVersion,
            String parserProfile,
            Map<String, Object> parserOptions
    ) {
        var sourceHash = java.util.HexFormat.of().formatHex(digest(SOURCE));
        var previousJobId = UUID.randomUUID();
        dsl.execute("""
                UPDATE document_version SET source_name = 'policy.pdf', content_hash = ? WHERE id = ?
                """, sourceHash, fixture.oldVersionId());
        dsl.execute("""
                INSERT INTO ingestion_job
                    (id, organization_id, knowledge_base_id, document_id, document_version_id,
                     status, attempt, idempotency_key, completed_at)
                SELECT ?, organization_id, knowledge_base_id, document_id, ?,
                       'SUCCEEDED', 1, ?, now()
                FROM ingestion_job WHERE id = ?
                """, previousJobId, fixture.oldVersionId(), "reusable-" + previousJobId, fixture.jobId());
        var artifact = normalized(ParseQualityReport.legacyPass(), parserVersion, Map.of(
                "requestedContentType", "application/pdf",
                "parserProfile", parserProfile,
                "parserOptions", parserOptions));
        dsl.execute("""
                INSERT INTO ingestion_artifact (job_id, artifact_type, payload, artifact_hash)
                VALUES (?, 'NORMALIZED_DOCUMENT', ?::jsonb, repeat('a', 64))
                """, previousJobId, json(artifact));
    }

    private byte[] digest(byte[] value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Fixture fixture() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var generationId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var oldVersionId = UUID.randomUUID();
        var newVersionId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var objectKey = "ingestion-test/" + newVersionId + "/policy.pdf";
        var policyJson = json(ChunkPolicy.defaults());

        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "org-" + organizationId);
        dsl.execute("""
                INSERT INTO knowledge_base (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, ?, '', ?::jsonb, now(), now())
                """, knowledgeBaseId, organizationId, "kb-" + knowledgeBaseId, policyJson);
        dsl.execute("""
                INSERT INTO index_generation
                    (id, knowledge_base_id, generation_number, status, embedding_model_id,
                     embedding_model_version, embedding_dimension, chunk_policy_version, activated_at)
                VALUES (?, ?, 1, 'ACTIVE', 'test-embedding', 'v1', 3, 'parent-child-v1', now())
                """, generationId, knowledgeBaseId);
        dsl.execute("""
                INSERT INTO document (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Policy', 'ACTIVE', now(), now())
                """, documentId, knowledgeBaseId, organizationId);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, source_type, content_hash, status,
                     published_at, created_at, updated_at)
                VALUES (?, ?, 1, 'policy-v1.pdf', 'PDF', repeat('a', 64), 'PUBLISHED', now(), now(), now())
                """, oldVersionId, documentId);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, source_type, content_hash, status,
                     created_at, updated_at)
                VALUES (?, ?, 2, 'policy.pdf', 'PDF', repeat('0', 64), 'PROCESSING', now(), now())
                """, newVersionId, documentId);
        dsl.execute("UPDATE document SET current_version_id = ? WHERE id = ?", oldVersionId, documentId);
        dsl.execute("""
                INSERT INTO document_asset
                    (document_version_id, object_key, file_name, content_type, byte_size, file_hash)
                VALUES (?, ?, 'policy.pdf', 'application/pdf', ?, repeat('0', 64))
                """, newVersionId, objectKey, SOURCE.length);
        dsl.execute("""
                INSERT INTO ingestion_job
                    (id, organization_id, knowledge_base_id, document_id, document_version_id,
                     status, idempotency_key)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                """, jobId, organizationId, knowledgeBaseId, documentId, newVersionId, "ingestion-" + jobId);
        for (var stage : List.of("PARSE", "NORMALIZE", "QUALITY", "CHUNK", "EMBED", "PUBLISH")) {
            dsl.execute("INSERT INTO ingestion_job_stage (job_id, stage, status) VALUES (?, ?, 'PENDING')",
                    jobId, stage);
        }
        return new Fixture(documentId, oldVersionId, newVersionId, jobId, objectKey);
    }

    private void assertPublished(Fixture fixture) {
        assertThat(value("SELECT current_version_id FROM document WHERE id = ?", fixture.documentId(), UUID.class))
                .isEqualTo(fixture.newVersionId());
        assertThat(value("SELECT status FROM document_version WHERE id = ?", fixture.oldVersionId(), String.class))
                .isEqualTo("SUPERSEDED");
        assertThat(value("SELECT status FROM document_version WHERE id = ?", fixture.newVersionId(), String.class))
                .isEqualTo("PUBLISHED");
    }

    private void installPointerSwitchFailure(UUID versionId) {
        dsl.execute("""
                CREATE OR REPLACE FUNCTION fail_ingestion_pointer_switch() RETURNS trigger AS $$
                BEGIN
                    IF NEW.current_version_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'injected document pointer switch failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(versionId));
        dsl.execute("""
                CREATE TRIGGER fail_ingestion_pointer_switch
                BEFORE UPDATE OF current_version_id ON document
                FOR EACH ROW EXECUTE FUNCTION fail_ingestion_pointer_switch()
                """);
    }

    private void removePointerSwitchFailure() {
        dsl.execute("DROP TRIGGER IF EXISTS fail_ingestion_pointer_switch ON document");
        dsl.execute("DROP FUNCTION IF EXISTS fail_ingestion_pointer_switch()");
    }

    private String stageStatus(UUID jobId, String stage) {
        return dsl.fetchOne("SELECT status FROM ingestion_job_stage WHERE job_id = ? AND stage = ?",
                jobId, stage).get(0, String.class);
    }

    private Integer stageAttempt(UUID jobId, String stage) {
        return dsl.fetchOne("SELECT attempt FROM ingestion_job_stage WHERE job_id = ? AND stage = ?",
                jobId, stage).get(0, Integer.class);
    }

    private <T> T value(String sql, UUID id, Class<T> type) {
        return dsl.fetchOne(sql, id).get(0, type);
    }

    private String json(Object value) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            UUID documentId,
            UUID oldVersionId,
            UUID newVersionId,
            UUID jobId,
            String objectKey
    ) { }

    private static final class FaultInjectingEmbeddings implements EmbeddingModelPort {
        private final AtomicInteger remainingFailures;
        private final AtomicInteger calls = new AtomicInteger();

        private FaultInjectingEmbeddings(int failures) {
            remainingFailures = new AtomicInteger(failures);
        }

        @Override
        public List<List<Float>> embed(EmbeddingModelReference model, List<String> texts) {
            calls.incrementAndGet();
            if (remainingFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("injected embedding failure");
            }
            return texts.stream().map(ignored -> List.of(0.1f, 0.2f, 0.3f)).toList();
        }

        private int calls() {
            return calls.get();
        }
    }
}
