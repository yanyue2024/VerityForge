package com.yanyue.rag.worker.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.domain.port.ObjectStoragePort;
import com.yanyue.rag.domain.port.PresignedUpload;
import com.yanyue.rag.domain.port.StoredObjectInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SupersededVersionCleanupTest {
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
    void removesUnreferencedContentAfterRetention() {
        var fixture = fixture(false);
        var storage = new RecordingStorage();

        new SupersededVersionCleanup(dsl, storage, 1, 50).cleanRetainedVersions();

        assertEquals(0, count("chunk_embedding", "chunk_id IN (SELECT id FROM chunk WHERE document_version_id = ?)",
                fixture.versionId));
        assertEquals(0, count("chunk", "document_version_id = ?", fixture.versionId));
        assertEquals(0, count("document_block", "document_version_id = ?", fixture.versionId));
        assertEquals(0, count("document_asset", "document_version_id = ?", fixture.versionId));
        assertEquals(0, count("ingestion_artifact",
                "job_id IN (SELECT id FROM ingestion_job WHERE document_version_id = ?)", fixture.versionId));
        assertTrue(storage.deleted.contains(fixture.objectKey));
        assertNotNull(dsl.fetchValue("SELECT search_index_cleaned_at FROM document_version WHERE id = ?",
                fixture.versionId, java.time.OffsetDateTime.class));
        assertNotNull(dsl.fetchValue("SELECT content_cleaned_at FROM document_version WHERE id = ?",
                fixture.versionId, java.time.OffsetDateTime.class));
    }

    @Test
    void retainsSourceContentUsedByHistoricalCitation() {
        var fixture = fixture(true);
        var storage = new RecordingStorage();

        new SupersededVersionCleanup(dsl, storage, 1, 50).cleanRetainedVersions();

        assertEquals(0, count("chunk_embedding", "chunk_id = ?", fixture.childChunkId));
        assertEquals(2, count("chunk", "document_version_id = ?", fixture.versionId));
        assertEquals(1, count("document_block", "document_version_id = ?", fixture.versionId));
        assertEquals(1, count("document_asset", "document_version_id = ?", fixture.versionId));
        assertTrue(storage.deleted.isEmpty());
        assertNotNull(dsl.fetchValue("SELECT search_index_cleaned_at FROM document_version WHERE id = ?",
                fixture.versionId, java.time.OffsetDateTime.class));
        assertNull(dsl.fetchValue("SELECT content_cleaned_at FROM document_version WHERE id = ?",
                fixture.versionId, java.time.OffsetDateTime.class));
    }

    private Fixture fixture(boolean cited) {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var generationId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        var parentChunkId = UUID.randomUUID();
        var childChunkId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var objectKey = "cleanup-test/" + versionId + "/source.pdf";

        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "cleanup-test");
        dsl.execute("""
                INSERT INTO knowledge_base (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, ?, '', '{}'::jsonb, now(), now())
                """, knowledgeBaseId, organizationId, "kb-" + knowledgeBaseId);
        dsl.execute("""
                INSERT INTO index_generation
                    (id, knowledge_base_id, generation_number, status, embedding_model_id,
                     embedding_model_version, embedding_dimension, chunk_policy_version)
                VALUES (?, ?, 1, 'ACTIVE', 'test', 'v1', 384, 'test-v1')
                """, generationId, knowledgeBaseId);
        dsl.execute("""
                INSERT INTO document (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'retained document', 'ACTIVE', now() - interval '2 days', now())
                """, documentId, knowledgeBaseId, organizationId);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, source_type, content_hash, status,
                     created_at, updated_at)
                VALUES (?, ?, 1, 'source.pdf', 'PDF', repeat('a', 64), 'SUPERSEDED',
                        now() - interval '2 days', now() - interval '2 days')
                """, versionId, documentId);
        dsl.execute("""
                INSERT INTO document_asset
                    (document_version_id, object_key, file_name, content_type, byte_size, file_hash)
                VALUES (?, ?, 'source.pdf', 'application/pdf', 10, repeat('b', 64))
                """, versionId, objectKey);
        dsl.execute("""
                INSERT INTO document_block
                    (id, document_version_id, block_type, order_index, block_text, block_hash)
                VALUES (?, ?, 'PARAGRAPH', 0, 'retained text', repeat('c', 64))
                """, blockId, versionId);
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, chunk_type, order_index, chunk_text, embedding_text,
                     estimated_tokens, source_block_ids, chunk_hash, chunk_policy_version)
                VALUES (?, ?, 'PARENT', 0, 'retained text', 'retained text', 2,
                        ARRAY[?::uuid], repeat('d', 64), 'test-v1')
                """, parentChunkId, versionId, blockId);
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, parent_chunk_id, chunk_type, order_index, chunk_text,
                     embedding_text, estimated_tokens, source_block_ids, chunk_hash, chunk_policy_version)
                VALUES (?, ?, ?, 'CHILD', 0, 'retained text', 'retained text', 2,
                        ARRAY[?::uuid], repeat('e', 64), 'test-v1')
                """, childChunkId, versionId, parentChunkId, blockId);
        dsl.execute("""
                INSERT INTO chunk_embedding
                    (chunk_id, index_generation_id, model_id, model_version, dimension, embedding, embedding_hash)
                VALUES (?, ?, 'test', 'v1', 384, ?::vector, repeat('f', 64))
                """, childChunkId, generationId, vector(384));
        dsl.execute("""
                INSERT INTO ingestion_job
                    (id, organization_id, knowledge_base_id, document_id, document_version_id,
                     status, idempotency_key, completed_at)
                VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', ?, now() - interval '2 days')
                """, jobId, organizationId, knowledgeBaseId, documentId, versionId, "cleanup-" + jobId);
        dsl.execute("""
                INSERT INTO ingestion_artifact (job_id, artifact_type, payload, artifact_hash)
                VALUES (?, 'NORMALIZED_DOCUMENT', '{}'::jsonb, repeat('1', 64))
                """, jobId);
        if (cited) {
            dsl.execute("""
                    INSERT INTO citation
                        (run_id, citation_index, document_id, document_version_id, chunk_id, quote_text)
                    VALUES (?, 1, ?, ?, ?, 'retained text')
                    """, UUID.randomUUID(), documentId, versionId, childChunkId);
        }
        return new Fixture(versionId, childChunkId, objectKey);
    }

    private int count(String table, String predicate, UUID id) {
        return dsl.fetchOne("SELECT count(*) FROM " + table + " WHERE " + predicate, id)
                .get(0, Integer.class);
    }

    private String vector(int dimension) {
        return "[" + String.join(",", java.util.Collections.nCopies(dimension, "0.1")) + "]";
    }

    private record Fixture(UUID versionId, UUID childChunkId, String objectKey) { }

    private static final class RecordingStorage implements ObjectStoragePort {
        private final List<String> deleted = new ArrayList<>();

        @Override
        public PresignedUpload presignPut(String objectKey, String contentType, Duration lifetime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredObjectInfo head(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteObject(String objectKey) {
            deleted.add(objectKey);
        }
    }
}
