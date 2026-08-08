package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.port.DocumentMetadataPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqDocumentMetadataAdapter implements DocumentMetadataPort {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqDocumentMetadataAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<MetadataContext> findContext(UUID organizationId, UUID userId, UUID documentVersionId) {
        return dsl.fetchOptional("""
                SELECT d.knowledge_base_id, d.id AS document_id, dv.id AS document_version_id,
                       (d.current_version_id = dv.id) AS current, dv.status,
                       dv.metadata::text AS metadata, dv.valid_from, dv.valid_to
                FROM document_version dv
                JOIN document d ON d.id = dv.document_id
                WHERE dv.id = ? AND d.organization_id = ? AND d.status <> 'DELETED'
                  AND document_is_accessible(d.id, ?)
                """, documentVersionId, organizationId, userId).map(record -> new MetadataContext(
                record.get("knowledge_base_id", UUID.class), record.get("document_id", UUID.class),
                record.get("document_version_id", UUID.class), Boolean.TRUE.equals(record.get("current", Boolean.class)),
                record.get("status", String.class), map(record.get("metadata", String.class)),
                instant(record.get("valid_from", OffsetDateTime.class)),
                instant(record.get("valid_to", OffsetDateTime.class))
        ));
    }

    @Override
    public MetadataRevision update(
            UUID organizationId,
            UUID changedBy,
            UUID documentVersionId,
            Map<String, Object> metadata,
            Instant validFrom,
            Instant validTo
    ) {
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var current = tx.fetchOptional("""
                    SELECT dv.metadata::text AS metadata, dv.valid_from, dv.valid_to
                    FROM document_version dv
                    JOIN document d ON d.id = dv.document_id
                    WHERE dv.id = ? AND d.organization_id = ? AND d.current_version_id = dv.id
                      AND dv.status = 'PUBLISHED' AND document_is_accessible(d.id, ?)
                    FOR UPDATE OF dv
                    """, documentVersionId, organizationId, changedBy)
                    .orElseThrow(() -> new IllegalArgumentException("Current published document version not found"));
            var revisionId = UUID.randomUUID();
            var updated = tx.fetchOne("""
                    UPDATE document_version
                    SET metadata = ?::jsonb, valid_from = ?::timestamptz, valid_to = ?::timestamptz,
                        updated_at = now()
                    WHERE id = ?
                    RETURNING updated_at
                    """, json(metadata), offset(validFrom), offset(validTo), documentVersionId);
            tx.execute("""
                    INSERT INTO document_metadata_revision
                        (id, document_version_id, changed_by, previous_metadata, new_metadata,
                         previous_valid_from, previous_valid_to, new_valid_from, new_valid_to)
                    VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::timestamptz, ?::timestamptz,
                            ?::timestamptz, ?::timestamptz)
                    """, revisionId, documentVersionId, changedBy, current.get("metadata", String.class), json(metadata),
                    current.get("valid_from", OffsetDateTime.class), current.get("valid_to", OffsetDateTime.class),
                    offset(validFrom), offset(validTo));
            return new MetadataRevision(revisionId, documentVersionId, Map.copyOf(metadata), validFrom, validTo,
                    updated.get("updated_at", OffsetDateTime.class).toInstant());
        });
    }

    private Map<String, Object> map(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Document metadata is invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize document metadata", exception);
        }
    }

    private OffsetDateTime offset(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
