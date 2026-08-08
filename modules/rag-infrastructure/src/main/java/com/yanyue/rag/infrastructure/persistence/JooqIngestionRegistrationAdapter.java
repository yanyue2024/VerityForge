package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.knowledge.UploadRegistration;
import com.yanyue.rag.domain.port.IngestionRegistrationPort;
import com.yanyue.rag.domain.port.StoredObjectInfo;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JooqIngestionRegistrationAdapter implements IngestionRegistrationPort {
    private static final String UNKNOWN_HASH = "0".repeat(64);

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqIngestionRegistrationAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void register(UploadRegistration registration) {
        var ownsKnowledgeBase = dsl.fetchOptional(
                "SELECT 1 FROM knowledge_base WHERE id = ? AND organization_id = ?",
                registration.knowledgeBaseId(), registration.organizationId()).isPresent();
        if (!ownsKnowledgeBase) throw new IllegalArgumentException("Knowledge base not found");
        var ownsActor = dsl.fetchOptional(
                "SELECT 1 FROM app_user WHERE id = ? AND organization_id = ? AND enabled = true",
                registration.actorUserId(), registration.organizationId()).isPresent();
        if (!ownsActor) throw new IllegalArgumentException("Upload actor not found");

        var now = OffsetDateTime.ofInstant(registration.createdAt(), ZoneOffset.UTC);
        var existingDocument = dsl.fetchOptional("""
                SELECT id FROM document
                WHERE id = ? AND knowledge_base_id = ? AND organization_id = ? AND status <> 'DELETED'
                  AND document_is_accessible(id, ?)
                FOR UPDATE
                """, registration.documentId(), registration.knowledgeBaseId(), registration.organizationId(),
                registration.actorUserId());
        if (existingDocument.isEmpty()) {
            var idAlreadyExists = dsl.fetchExists(dsl.selectOne().from("document")
                    .where(org.jooq.impl.DSL.field("id").eq(registration.documentId())));
            if (idAlreadyExists) throw new IllegalArgumentException("Document not found");
            dsl.execute("""
                    INSERT INTO document
                        (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?::timestamptz, ?::timestamptz)
                    """, registration.documentId(), registration.knowledgeBaseId(), registration.organizationId(),
                    registration.title(), now, now);
        } else {
            dsl.execute("UPDATE document SET title = ?, updated_at = ?::timestamptz WHERE id = ?",
                    registration.title(), now, registration.documentId());
        }
        var versionNumber = dsl.fetchOptional("""
                SELECT COALESCE(max(version_number), 0) + 1 AS next_version
                FROM document_version WHERE document_id = ?
                """, registration.documentId()).map(record -> record.get("next_version", Integer.class)).orElse(1);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, source_type, content_hash, metadata, status,
                     valid_from, valid_to, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'DRAFT',
                        ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """, registration.documentVersionId(), registration.documentId(), versionNumber, registration.fileName(),
                sourceType(registration.fileName()), hash(registration.declaredSha256()), json(registration.metadata()),
                offset(registration.validFrom()), offset(registration.validTo()), now, now);
        dsl.execute("""
                INSERT INTO document_asset
                    (id, document_version_id, object_key, file_name, content_type, byte_size, file_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, registration.uploadId(), registration.documentVersionId(), registration.objectKey(),
                registration.fileName(), registration.contentType(), registration.byteSize(), hash(registration.declaredSha256()));
    }

    @Override
    public Optional<UploadRegistration> find(UUID organizationId, UUID userId, UUID uploadId) {
        return dsl.fetchOptional("""
                SELECT a.id AS upload_id, d.organization_id, ?::uuid AS actor_user_id,
                       d.knowledge_base_id, d.id AS document_id,
                       dv.id AS document_version_id, d.title, a.file_name, a.content_type, a.byte_size,
                       NULLIF(a.file_hash, ?) AS declared_sha256, a.object_key, dv.metadata::text AS metadata,
                       dv.valid_from, dv.valid_to, a.created_at
                FROM document_asset a
                JOIN document_version dv ON dv.id = a.document_version_id
                JOIN document d ON d.id = dv.document_id
                WHERE a.id = ? AND d.organization_id = ? AND document_is_accessible(d.id, ?)
                """, userId, UNKNOWN_HASH, uploadId, organizationId, userId).map(this::map);
    }

    @Override
    @Transactional
    public UUID completeAndEnqueue(UploadRegistration registration, StoredObjectInfo storedObject) {
        var existing = dsl.fetchOptional("""
                SELECT id FROM ingestion_job WHERE idempotency_key = ?
                """, "upload:" + registration.uploadId());
        if (existing.isPresent()) return existing.get().get("id", UUID.class);

        var jobId = UUID.randomUUID();
        dsl.execute("UPDATE document_version SET status = 'PROCESSING', updated_at = now() WHERE id = ? AND status = 'DRAFT'",
                registration.documentVersionId());
        dsl.execute("""
                INSERT INTO ingestion_job
                    (id, organization_id, knowledge_base_id, document_id, document_version_id, status, current_stage, idempotency_key)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 'PARSE', ?)
                """, jobId, registration.organizationId(), registration.knowledgeBaseId(), registration.documentId(),
                registration.documentVersionId(), "upload:" + registration.uploadId());
        for (var stage : java.util.List.of("PARSE", "NORMALIZE", "QUALITY", "CHUNK", "EMBED", "PUBLISH")) {
            dsl.execute("INSERT INTO ingestion_job_stage (job_id, stage, status) VALUES (?, ?, 'PENDING')", jobId, stage);
        }
        dsl.execute("""
                INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
                VALUES ('IngestionJob', ?, 'ingestion.requested', jsonb_build_object('jobId', ?::text))
                """, jobId, jobId.toString());
        return jobId;
    }

    private UploadRegistration map(Record record) {
        try {
            return new UploadRegistration(
                    record.get("upload_id", UUID.class), record.get("organization_id", UUID.class),
                    record.get("actor_user_id", UUID.class),
                    record.get("knowledge_base_id", UUID.class), record.get("document_id", UUID.class),
                    record.get("document_version_id", UUID.class), record.get("title", String.class),
                    record.get("file_name", String.class), record.get("content_type", String.class),
                    record.get("byte_size", Long.class), record.get("declared_sha256", String.class),
                    record.get("object_key", String.class),
                    objectMapper.readValue(record.get("metadata", String.class), new TypeReference<Map<String, Object>>() { }),
                    instant(record.get("valid_from", OffsetDateTime.class)), instant(record.get("valid_to", OffsetDateTime.class)),
                    record.get("created_at", OffsetDateTime.class).toInstant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid upload metadata", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize metadata", exception);
        }
    }

    private String sourceType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "UNKNOWN" : fileName.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
    }

    private String hash(String value) { return value == null ? UNKNOWN_HASH : value; }
    private OffsetDateTime offset(java.time.Instant value) { return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
    private java.time.Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}
