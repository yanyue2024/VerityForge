package com.yanyue.rag.infrastructure.persistence;

import com.yanyue.rag.contract.knowledge.IndexGenerationStatus;
import com.yanyue.rag.contract.knowledge.IndexRebuildStatus;
import com.yanyue.rag.domain.port.IndexGenerationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqIndexGenerationRepository implements IndexGenerationRepository {
    private final DSLContext dsl;

    public JooqIndexGenerationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean knowledgeBaseExists(UUID organizationId, UUID knowledgeBaseId) {
        return dsl.fetchExists(dsl.selectOne().from("knowledge_base")
                .where(org.jooq.impl.DSL.field("id").eq(knowledgeBaseId))
                .and(org.jooq.impl.DSL.field("organization_id").eq(organizationId)));
    }

    @Override
    public boolean hasActiveRebuild(UUID knowledgeBaseId) {
        return dsl.fetchExists(dsl.selectOne().from("index_rebuild_job")
                .where(org.jooq.impl.DSL.field("knowledge_base_id").eq(knowledgeBaseId))
                .and(org.jooq.impl.DSL.field("status").in("QUEUED", "RUNNING")));
    }

    @Override
    public String chunkPolicyVersion(UUID knowledgeBaseId) {
        return dsl.fetchOptional("SELECT chunk_policy ->> 'version' FROM knowledge_base WHERE id = ?", knowledgeBaseId)
                .map(record -> record.get(0, String.class))
                .filter(value -> value != null && !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Knowledge base chunk policy has no version"));
    }

    @Override
    public GenerationRecord createBuildingGeneration(
            UUID knowledgeBaseId,
            UUID embeddingProfileId,
            String modelId,
            String modelVersion,
            int dimension,
            String chunkPolicyVersion
    ) {
        var record = dsl.fetchOne("""
                INSERT INTO index_generation
                    (knowledge_base_id, generation_number, status, embedding_profile_id,
                     embedding_model_id, embedding_model_version, embedding_dimension, chunk_policy_version)
                VALUES (?, COALESCE((SELECT max(generation_number) + 1 FROM index_generation
                                     WHERE knowledge_base_id = ?), 1),
                        'BUILDING', ?, ?, ?, ?, ?)
                RETURNING id, knowledge_base_id, generation_number, status, embedding_profile_id,
                          embedding_model_id, embedding_model_version, embedding_dimension,
                          chunk_policy_version, created_at, activated_at, retired_at
                """, knowledgeBaseId, knowledgeBaseId, embeddingProfileId, modelId, modelVersion, dimension,
                chunkPolicyVersion);
        return mapGeneration(record, 0L);
    }

    @Override
    public RebuildJobRecord createRebuildJob(UUID organizationId, UUID knowledgeBaseId, UUID generationId) {
        var record = dsl.fetchOne("""
                INSERT INTO index_rebuild_job
                    (organization_id, knowledge_base_id, index_generation_id, status, total_chunks)
                VALUES (?, ?, ?, 'QUEUED', (
                    SELECT count(*)
                    FROM chunk c
                    JOIN document_version dv ON dv.id = c.document_version_id
                    JOIN document d ON d.id = dv.document_id
                    WHERE d.knowledge_base_id = ?
                      AND d.status = 'ACTIVE'
                      AND d.current_version_id = dv.id
                      AND dv.status = 'PUBLISHED'
                      AND c.chunk_type = 'CHILD'
                      AND c.enabled = true
                ))
                RETURNING id, index_generation_id, status, total_chunks, completed_chunks,
                          reused_chunks, failed_chunks, attempt, max_attempts, next_attempt_at,
                          error_message, started_at, completed_at, created_at
                """, organizationId, knowledgeBaseId, generationId, knowledgeBaseId);
        return mapJob(record);
    }

    @Override
    public List<GenerationRecord> findAll(UUID organizationId, UUID knowledgeBaseId) {
        return dsl.fetch("""
                SELECT ig.id, ig.knowledge_base_id, ig.generation_number, ig.status, ig.embedding_profile_id,
                       ig.embedding_model_id, ig.embedding_model_version, ig.embedding_dimension,
                       ig.chunk_policy_version, ig.created_at, ig.activated_at, ig.retired_at,
                       count(ce.id) AS vector_count
                FROM index_generation ig
                JOIN knowledge_base kb ON kb.id = ig.knowledge_base_id
                LEFT JOIN chunk_embedding ce ON ce.index_generation_id = ig.id
                WHERE kb.organization_id = ? AND ig.knowledge_base_id = ?
                GROUP BY ig.id
                ORDER BY ig.generation_number DESC
                """, organizationId, knowledgeBaseId).map(record -> mapGeneration(
                record, record.get("vector_count", Long.class)));
    }

    @Override
    public GenerationRecord activate(UUID organizationId, UUID knowledgeBaseId, UUID generationId) {
        if (dsl.fetchExists(dsl.selectOne().from("ingestion_job")
                .where(org.jooq.impl.DSL.field("knowledge_base_id").eq(knowledgeBaseId))
                .and(org.jooq.impl.DSL.field("status").in("PENDING", "RUNNING")))) {
            throw new IllegalArgumentException("Cannot activate a Generation while document ingestion is active");
        }
        var target = dsl.fetchOptional("""
                SELECT ig.id, ig.knowledge_base_id, ig.generation_number, ig.status, ig.embedding_profile_id,
                       ig.embedding_model_id, ig.embedding_model_version, ig.embedding_dimension,
                       ig.chunk_policy_version, ig.created_at, ig.activated_at, ig.retired_at
                FROM index_generation ig
                JOIN knowledge_base kb ON kb.id = ig.knowledge_base_id
                WHERE kb.organization_id = ? AND ig.knowledge_base_id = ? AND ig.id = ?
                FOR UPDATE OF ig
                """, organizationId, knowledgeBaseId, generationId)
                .orElseThrow(() -> new IllegalArgumentException("Index Generation not found"));
        var status = IndexGenerationStatus.valueOf(target.get("status", String.class));
        if (status == IndexGenerationStatus.ACTIVE) {
            return mapGeneration(target, vectorCount(generationId));
        }
        if (status != IndexGenerationStatus.RETIRED) {
            throw new IllegalArgumentException("Only a RETIRED Generation can be activated");
        }
        var expected = currentChunkCount(knowledgeBaseId);
        var available = dsl.fetchCount(dsl.selectOne()
                .from("chunk_embedding ce")
                .join("chunk c").on("c.id = ce.chunk_id")
                .join("document_version dv").on("dv.id = c.document_version_id")
                .join("document d").on("d.id = dv.document_id")
                .where(org.jooq.impl.DSL.field("ce.index_generation_id").eq(generationId))
                .and("d.knowledge_base_id = ?", knowledgeBaseId)
                .and("d.status = 'ACTIVE'")
                .and("d.current_version_id = dv.id")
                .and("dv.status = 'PUBLISHED'")
                .and("c.chunk_type = 'CHILD'")
                .and("c.enabled = true"));
        if (available != expected) {
            throw new IllegalArgumentException("Retired Generation does not cover every current Chunk");
        }
        dsl.execute("""
                UPDATE index_generation
                SET status = 'RETIRED', retired_at = now()
                WHERE knowledge_base_id = ? AND status = 'ACTIVE' AND id <> ?
                """, knowledgeBaseId, generationId);
        dsl.execute("""
                UPDATE index_generation
                SET status = 'ACTIVE', activated_at = now(), retired_at = NULL
                WHERE id = ? AND status = 'RETIRED'
                """, generationId);
        var activated = dsl.fetchOne("""
                SELECT id, knowledge_base_id, generation_number, status, embedding_profile_id,
                       embedding_model_id, embedding_model_version, embedding_dimension,
                       chunk_policy_version, created_at, activated_at, retired_at
                FROM index_generation WHERE id = ?
                """, generationId);
        return mapGeneration(activated, vectorCount(generationId));
    }

    @Override
    public Optional<RebuildJobRecord> findJobByGeneration(UUID generationId) {
        return dsl.fetchOptional("""
                SELECT id, index_generation_id, status, total_chunks, completed_chunks,
                       reused_chunks, failed_chunks, attempt, max_attempts, next_attempt_at,
                       error_message, started_at, completed_at, created_at
                FROM index_rebuild_job WHERE index_generation_id = ?
                """, generationId).map(this::mapJob);
    }

    private GenerationRecord mapGeneration(Record record, long vectorCount) {
        return new GenerationRecord(
                record.get("id", UUID.class),
                record.get("knowledge_base_id", UUID.class),
                record.get("generation_number", Integer.class),
                IndexGenerationStatus.valueOf(record.get("status", String.class)),
                record.get("embedding_profile_id", UUID.class),
                record.get("embedding_model_id", String.class),
                record.get("embedding_model_version", String.class),
                record.get("embedding_dimension", Integer.class),
                record.get("chunk_policy_version", String.class),
                vectorCount,
                instant(record.get("created_at", OffsetDateTime.class)),
                instant(record.get("activated_at", OffsetDateTime.class)),
                instant(record.get("retired_at", OffsetDateTime.class))
        );
    }

    private RebuildJobRecord mapJob(Record record) {
        return new RebuildJobRecord(
                record.get("id", UUID.class),
                record.get("index_generation_id", UUID.class),
                IndexRebuildStatus.valueOf(record.get("status", String.class)),
                record.get("total_chunks", Integer.class),
                record.get("completed_chunks", Integer.class),
                record.get("reused_chunks", Integer.class),
                record.get("failed_chunks", Integer.class),
                record.get("attempt", Integer.class),
                record.get("max_attempts", Integer.class),
                instant(record.get("next_attempt_at", OffsetDateTime.class)),
                record.get("error_message", String.class),
                instant(record.get("started_at", OffsetDateTime.class)),
                instant(record.get("completed_at", OffsetDateTime.class)),
                instant(record.get("created_at", OffsetDateTime.class))
        );
    }

    private int currentChunkCount(UUID knowledgeBaseId) {
        return dsl.fetchCount(dsl.selectOne()
                .from("chunk c")
                .join("document_version dv").on("dv.id = c.document_version_id")
                .join("document d").on("d.id = dv.document_id")
                .where(org.jooq.impl.DSL.field("d.knowledge_base_id").eq(knowledgeBaseId))
                .and("d.status = 'ACTIVE'")
                .and("d.current_version_id = dv.id")
                .and("dv.status = 'PUBLISHED'")
                .and("c.chunk_type = 'CHILD'")
                .and("c.enabled = true"));
    }

    private int vectorCount(UUID generationId) {
        return dsl.fetchCount(dsl.selectOne().from("chunk_embedding")
                .where(org.jooq.impl.DSL.field("index_generation_id").eq(generationId)));
    }

    private java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
