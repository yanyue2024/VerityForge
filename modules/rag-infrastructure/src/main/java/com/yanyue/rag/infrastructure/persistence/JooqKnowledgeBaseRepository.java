package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.knowledge.ChunkPolicy;
import com.yanyue.rag.domain.knowledge.KnowledgeBase;
import com.yanyue.rag.domain.port.KnowledgeBaseRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqKnowledgeBaseRepository implements KnowledgeBaseRepository {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqKnowledgeBaseRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public KnowledgeBase save(KnowledgeBase knowledgeBase) {
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::timestamptz, ?::timestamptz)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    chunk_policy = EXCLUDED.chunk_policy,
                    updated_at = EXCLUDED.updated_at
                """,
                knowledgeBase.id(), knowledgeBase.organizationId(), knowledgeBase.name(), knowledgeBase.description(),
                json(knowledgeBase.chunkPolicy()), OffsetDateTime.ofInstant(knowledgeBase.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(knowledgeBase.updatedAt(), ZoneOffset.UTC));
        return knowledgeBase;
    }

    @Override
    public Optional<KnowledgeBase> findById(UUID organizationId, UUID id) {
        return dsl.fetchOptional("""
                SELECT id, organization_id, name, description, chunk_policy::text AS chunk_policy, created_at, updated_at
                FROM knowledge_base WHERE organization_id = ? AND id = ?
                """, organizationId, id).map(this::map);
    }

    @Override
    public List<KnowledgeBase> findAll(UUID organizationId) {
        return dsl.fetch("""
                SELECT id, organization_id, name, description, chunk_policy::text AS chunk_policy, created_at, updated_at
                FROM knowledge_base WHERE organization_id = ? ORDER BY updated_at DESC
                """, organizationId).map(this::map);
    }

    @Override
    public java.util.Map<UUID, KnowledgeBaseCounts> counts(UUID organizationId, UUID userId) {
        return dsl.fetch("""
                SELECT kb.id,
                       COALESCE(document_stats.document_count, 0) AS document_count,
                       COALESCE(chunk_stats.chunk_count, 0) AS chunk_count,
                       COALESCE(document_stats.ready_count, 0) AS ready_count,
                       COALESCE(document_stats.processing_count, 0) AS processing_count,
                       COALESCE(document_stats.failed_count, 0) AS failed_count,
                       GREATEST(
                           kb.updated_at,
                           COALESCE(document_stats.activity_at, kb.updated_at),
                           COALESCE(job_stats.activity_at, kb.updated_at)
                       ) AS activity_at
                FROM knowledge_base kb
                LEFT JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE d.status <> 'DELETED') AS document_count,
                           count(*) FILTER (
                               WHERE d.status = 'ACTIVE' AND dv.status = 'PUBLISHED'
                           ) AS ready_count,
                           count(*) FILTER (
                               WHERE d.status = 'ACTIVE' AND dv.status = 'PROCESSING'
                           ) AS processing_count,
                           count(*) FILTER (
                               WHERE d.status = 'ACTIVE' AND dv.status = 'FAILED'
                           ) AS failed_count,
                           max(GREATEST(d.updated_at, COALESCE(dv.updated_at, d.updated_at))) AS activity_at
                    FROM document d
                    LEFT JOIN document_version dv ON dv.id = d.current_version_id
                    WHERE d.knowledge_base_id = kb.id
                      AND document_is_accessible(d.id, ?)
                ) document_stats ON true
                LEFT JOIN LATERAL (
                    SELECT count(c.id) AS chunk_count
                    FROM document d
                    JOIN document_version dv ON dv.id = d.current_version_id AND dv.status = 'PUBLISHED'
                    JOIN chunk c ON c.document_version_id = dv.id
                        AND c.chunk_type = 'CHILD' AND c.enabled = true
                    WHERE d.knowledge_base_id = kb.id
                      AND d.status = 'ACTIVE'
                      AND document_is_accessible(d.id, ?)
                ) chunk_stats ON true
                LEFT JOIN LATERAL (
                    SELECT max(GREATEST(job.created_at,
                                        COALESCE(job.started_at, job.created_at),
                                        COALESCE(job.completed_at, job.created_at))) AS activity_at
                    FROM ingestion_job job
                    WHERE job.knowledge_base_id = kb.id
                ) job_stats ON true
                WHERE kb.organization_id = ?
                ORDER BY activity_at DESC, kb.name
                """, userId, userId, organizationId).intoMap(
                record -> record.get("id", UUID.class),
                record -> new KnowledgeBaseCounts(
                        record.get("document_count", Long.class),
                        record.get("chunk_count", Long.class),
                        record.get("ready_count", Long.class),
                        record.get("processing_count", Long.class),
                        record.get("failed_count", Long.class),
                        record.get("activity_at", OffsetDateTime.class).toInstant()
                )
        );
    }

    @Override
    public Optional<KnowledgeBaseDeletion> delete(UUID organizationId, UUID id) {
        var knowledgeBase = dsl.fetchOptional("""
                SELECT id
                FROM knowledge_base
                WHERE organization_id = ? AND id = ?
                FOR UPDATE
                """, organizationId, id);
        if (knowledgeBase.isEmpty()) return Optional.empty();

        var objectKeys = dsl.fetch("""
                SELECT asset.object_key
                FROM document_asset asset
                JOIN document_version version ON version.id = asset.document_version_id
                JOIN document document ON document.id = version.document_id
                WHERE document.knowledge_base_id = ?
                ORDER BY asset.object_key
                """, id).getValues("object_key", String.class);

        dsl.execute("""
                DELETE FROM outbox_event
                WHERE aggregate_id IN (
                    SELECT job.id FROM ingestion_job job WHERE job.knowledge_base_id = ?
                )
                """, id);
        dsl.execute("DELETE FROM agent_knowledge_reference WHERE knowledge_base_id = ?", id);
        dsl.execute("""
                DELETE FROM agent_goal_ranked_candidate candidate
                USING document document
                WHERE candidate.document_id = document.id AND document.knowledge_base_id = ?
                """, id);
        dsl.execute("""
                DELETE FROM retrieval_candidate candidate
                USING chunk chunk, document_version version, document document
                WHERE candidate.chunk_id = chunk.id
                  AND chunk.document_version_id = version.id
                  AND version.document_id = document.id
                  AND document.knowledge_base_id = ?
                """, id);
        dsl.execute("""
                DELETE FROM citation citation
                USING document document
                WHERE citation.document_id = document.id AND document.knowledge_base_id = ?
                """, id);
        dsl.execute("""
                DELETE FROM evidence_item evidence
                USING document document
                WHERE evidence.document_id = document.id AND document.knowledge_base_id = ?
                """, id);
        dsl.execute("DELETE FROM ingestion_job WHERE knowledge_base_id = ?", id);
        dsl.execute("UPDATE document SET current_version_id = NULL WHERE knowledge_base_id = ?", id);
        dsl.execute("""
                DELETE FROM document_version version
                USING document document
                WHERE version.document_id = document.id AND document.knowledge_base_id = ?
                """, id);
        dsl.execute("DELETE FROM document WHERE knowledge_base_id = ?", id);
        dsl.execute("DELETE FROM knowledge_base WHERE organization_id = ? AND id = ?", organizationId, id);

        return Optional.of(new KnowledgeBaseDeletion(List.copyOf(objectKeys)));
    }

    private KnowledgeBase map(Record record) {
        try {
            return new KnowledgeBase(
                    record.get("id", UUID.class),
                    record.get("organization_id", UUID.class),
                    record.get("name", String.class),
                    record.get("description", String.class),
                    objectMapper.readValue(record.get("chunk_policy", String.class), ChunkPolicy.class),
                    record.get("created_at", OffsetDateTime.class).toInstant(),
                    record.get("updated_at", OffsetDateTime.class).toInstant()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted chunk policy", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize value", exception);
        }
    }
}
