package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.knowledge.MetadataField;
import com.yanyue.rag.domain.knowledge.MetadataSchema;
import com.yanyue.rag.domain.port.MetadataSchemaRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqMetadataSchemaRepository implements MetadataSchemaRepository {
    private static final TypeReference<List<MetadataField>> FIELDS_TYPE = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqMetadataSchemaRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredMetadataSchema> findActive(UUID organizationId, UUID knowledgeBaseId) {
        return dsl.fetchOptional("""
                SELECT ms.id, ms.knowledge_base_id, ms.version, ms.fields::text AS fields,
                       ms.active, ms.created_at
                FROM metadata_schema ms
                JOIN knowledge_base kb ON kb.id = ms.knowledge_base_id
                WHERE ms.knowledge_base_id = ? AND kb.organization_id = ? AND ms.active = true
                """, knowledgeBaseId, organizationId).map(this::map);
    }

    @Override
    public List<StoredMetadataSchema> findAll(UUID organizationId, UUID knowledgeBaseId) {
        return dsl.fetch("""
                SELECT ms.id, ms.knowledge_base_id, ms.version, ms.fields::text AS fields,
                       ms.active, ms.created_at
                FROM metadata_schema ms
                JOIN knowledge_base kb ON kb.id = ms.knowledge_base_id
                WHERE ms.knowledge_base_id = ? AND kb.organization_id = ?
                ORDER BY ms.version DESC
                """, knowledgeBaseId, organizationId).map(this::map);
    }

    @Override
    public StoredMetadataSchema activate(UUID organizationId, UUID knowledgeBaseId, MetadataSchema schema) {
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var owned = tx.fetchOptional("""
                    SELECT id FROM knowledge_base WHERE id = ? AND organization_id = ? FOR UPDATE
                    """, knowledgeBaseId, organizationId).isPresent();
            if (!owned) throw new IllegalArgumentException("Knowledge base not found");
            var nextVersion = tx.fetchValue("""
                    SELECT COALESCE(max(version), 0) + 1 FROM metadata_schema WHERE knowledge_base_id = ?
                    """, knowledgeBaseId, Integer.class);
            tx.execute("UPDATE metadata_schema SET active = false WHERE knowledge_base_id = ? AND active = true",
                    knowledgeBaseId);
            var record = tx.fetchOne("""
                    INSERT INTO metadata_schema (id, knowledge_base_id, version, fields, active)
                    VALUES (?, ?, ?, ?::jsonb, true)
                    RETURNING id, knowledge_base_id, version, fields::text AS fields, active, created_at
                    """, UUID.randomUUID(), knowledgeBaseId, nextVersion, json(schema.fields()));
            return map(record);
        });
    }

    @Override
    public boolean deactivate(UUID organizationId, UUID knowledgeBaseId) {
        return dsl.execute("""
                UPDATE metadata_schema ms SET active = false
                FROM knowledge_base kb
                WHERE ms.knowledge_base_id = kb.id AND ms.knowledge_base_id = ?
                  AND kb.organization_id = ? AND ms.active = true
                """, knowledgeBaseId, organizationId) > 0;
    }

    @Override
    public Optional<StoredMetadataSchema> findActiveForOrganization(UUID organizationId) {
        return dsl.fetchOptional("""
                SELECT id, NULL::uuid AS knowledge_base_id, version, fields::text AS fields,
                       active, created_at
                FROM organization_metadata_schema
                WHERE organization_id = ? AND active = true
                """, organizationId).map(this::map);
    }

    @Override
    public List<StoredMetadataSchema> findAllForOrganization(UUID organizationId) {
        return dsl.fetch("""
                SELECT id, NULL::uuid AS knowledge_base_id, version, fields::text AS fields,
                       active, created_at
                FROM organization_metadata_schema
                WHERE organization_id = ? ORDER BY version DESC
                """, organizationId).map(this::map);
    }

    @Override
    public StoredMetadataSchema activateForOrganization(UUID organizationId, List<MetadataField> fields) {
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var owned = tx.fetchOptional("SELECT id FROM organization WHERE id = ? FOR UPDATE", organizationId)
                    .isPresent();
            if (!owned) throw new IllegalArgumentException("Organization not found");
            var nextVersion = tx.fetchValue("""
                    SELECT COALESCE(max(version), 0) + 1
                    FROM organization_metadata_schema WHERE organization_id = ?
                    """, organizationId, Integer.class);
            tx.execute("UPDATE organization_metadata_schema SET active = false WHERE organization_id = ? AND active = true",
                    organizationId);
            var record = tx.fetchOne("""
                    INSERT INTO organization_metadata_schema (id, organization_id, version, fields, active)
                    VALUES (?, ?, ?, ?::jsonb, true)
                    RETURNING id, NULL::uuid AS knowledge_base_id, version, fields::text AS fields, active, created_at
                    """, UUID.randomUUID(), organizationId, nextVersion, json(fields));
            for (var knowledgeBaseId : tx.fetch("""
                    SELECT id FROM knowledge_base WHERE organization_id = ? ORDER BY id
                    """, organizationId).getValues("id", UUID.class)) {
                activateInTransaction(tx, knowledgeBaseId, fields);
            }
            return map(record);
        });
    }

    @Override
    public void inheritOrganizationSchema(UUID organizationId, UUID knowledgeBaseId) {
        dsl.transaction(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var record = tx.fetchOptional("""
                    SELECT fields::text AS fields FROM organization_metadata_schema
                    WHERE organization_id = ? AND active = true
                    """, organizationId).orElse(null);
            if (record == null) return;
            try {
                activateInTransaction(tx, knowledgeBaseId,
                        objectMapper.readValue(record.get("fields", String.class), FIELDS_TYPE));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Organization Metadata Schema JSON is invalid", exception);
            }
        });
    }

    private void activateInTransaction(DSLContext tx, UUID knowledgeBaseId, List<MetadataField> fields) {
        var nextVersion = tx.fetchValue("""
                SELECT COALESCE(max(version), 0) + 1 FROM metadata_schema WHERE knowledge_base_id = ?
                """, knowledgeBaseId, Integer.class);
        tx.execute("UPDATE metadata_schema SET active = false WHERE knowledge_base_id = ? AND active = true",
                knowledgeBaseId);
        tx.execute("""
                INSERT INTO metadata_schema (id, knowledge_base_id, version, fields, active)
                VALUES (?, ?, ?, ?::jsonb, true)
                """, UUID.randomUUID(), knowledgeBaseId, nextVersion, json(fields));
    }

    private StoredMetadataSchema map(Record record) {
        try {
            var knowledgeBaseId = record.get("knowledge_base_id", UUID.class);
            var schema = new MetadataSchema(knowledgeBaseId, record.get("version", Integer.class),
                    objectMapper.readValue(record.get("fields", String.class), FIELDS_TYPE));
            return new StoredMetadataSchema(record.get("id", UUID.class), schema,
                    Boolean.TRUE.equals(record.get("active", Boolean.class)),
                    record.get("created_at", OffsetDateTime.class).toInstant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Metadata Schema JSON is invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize Metadata Schema", exception);
        }
    }
}
