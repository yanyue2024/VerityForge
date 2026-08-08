package com.yanyue.rag.worker.index;

import com.yanyue.rag.domain.model.EmbeddingModelReference;
import com.yanyue.rag.domain.port.EmbeddingModelPort;
import com.yanyue.rag.infrastructure.retrieval.PgVectorFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IndexRebuildProcessor {
    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final EmbeddingModelPort embeddings;
    private final int batchSize;

    public IndexRebuildProcessor(
            DSLContext dsl,
            TransactionTemplate transactions,
            EmbeddingModelPort embeddings,
            @Value("${rag.index-rebuild.batch-size:32}") int batchSize
    ) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.embeddings = embeddings;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${rag.index-rebuild.poll-delay-ms:1000}")
    public void processNext() {
        var context = transactions.execute(ignored -> claim());
        if (context == null) return;
        try {
            rebuild(context);
            transactions.executeWithoutResult(ignored -> activate(context));
        } catch (RuntimeException exception) {
            fail(context, exception);
        }
    }

    private RebuildContext claim() {
        var record = dsl.fetchOptional("""
                SELECT j.id AS job_id, j.knowledge_base_id, j.index_generation_id, j.attempt,
                       ig.embedding_profile_id, ig.embedding_model_id, ig.embedding_model_version,
                       ig.embedding_dimension, ig.chunk_policy_version
                FROM index_rebuild_job j
                JOIN index_generation ig ON ig.id = j.index_generation_id
                WHERE j.status = 'QUEUED' AND j.next_attempt_at <= now() AND ig.status = 'BUILDING'
                ORDER BY j.next_attempt_at, j.created_at
                FOR UPDATE OF j, ig SKIP LOCKED
                LIMIT 1
                """).orElse(null);
        if (record == null) return null;
        dsl.execute("""
                UPDATE index_rebuild_job
                SET status = 'RUNNING', attempt = attempt + 1,
                    started_at = COALESCE(started_at, now()), completed_at = NULL,
                    updated_at = now()
                WHERE id = ?
                """, record.get("job_id", UUID.class));
        return new RebuildContext(
                record.get("job_id", UUID.class),
                record.get("knowledge_base_id", UUID.class),
                record.get("index_generation_id", UUID.class),
                record.get("attempt", Integer.class) + 1,
                new EmbeddingModelReference(
                        record.get("embedding_profile_id", UUID.class),
                        record.get("embedding_model_id", String.class),
                        record.get("embedding_model_version", String.class),
                        record.get("embedding_dimension", Integer.class)
                ),
                record.get("chunk_policy_version", String.class)
        );
    }

    private void rebuild(RebuildContext context) {
        var reused = transactions.execute(ignored -> reuseEmbeddings(context));
        transactions.executeWithoutResult(ignored -> dsl.execute("""
                UPDATE index_rebuild_job
                SET reused_chunks = reused_chunks + ?,
                    total_chunks = ?, completed_chunks = ?, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND attempt = ?
                """, reused, targetChunkCount(context), coveredChunkCount(context), context.jobId(), context.attempt()));

        while (true) {
            var missing = missing(context, batchSize);
            if (missing.isEmpty()) return;
            var vectors = embeddings.embed(context.model(),
                    missing.stream().map(record -> record.get("embedding_text", String.class)).toList());
            if (vectors.size() != missing.size()) {
                throw new IllegalStateException("Embedding model returned an incomplete rebuild batch");
            }
            transactions.executeWithoutResult(ignored -> {
                for (int index = 0; index < missing.size(); index++) {
                    var chunk = missing.get(index);
                    dsl.execute("""
                            INSERT INTO chunk_embedding
                                (chunk_id, index_generation_id, model_id, model_version,
                                 dimension, embedding, embedding_hash)
                            VALUES (?, ?, ?, ?, ?, ?::vector, ?)
                            ON CONFLICT (chunk_id, index_generation_id) DO NOTHING
                            """,
                            chunk.get("id", UUID.class),
                            context.generationId(),
                            context.model().modelId(),
                            context.model().modelVersion(),
                            context.model().dimension(),
                            PgVectorFormatter.format(vectors.get(index)),
                            sha256(context.model().modelId() + ":" + context.model().modelVersion() + ":"
                                    + chunk.get("chunk_hash", String.class))
                    );
                }
                var completed = coveredChunkCount(context);
                dsl.execute("""
                        UPDATE index_rebuild_job
                        SET total_chunks = ?, completed_chunks = ?, updated_at = now()
                        WHERE id = ? AND status = 'RUNNING' AND attempt = ?
                        """, targetChunkCount(context), completed, context.jobId(), context.attempt());
            });
        }
    }

    private int reuseEmbeddings(RebuildContext context) {
        return dsl.execute("""
                INSERT INTO chunk_embedding
                    (chunk_id, index_generation_id, model_id, model_version,
                     dimension, embedding, embedding_hash)
                SELECT target.id, ?, source_embedding.model_id, source_embedding.model_version,
                       source_embedding.dimension, source_embedding.embedding, source_embedding.embedding_hash
                FROM chunk target
                JOIN document_version target_version ON target_version.id = target.document_version_id
                JOIN document target_document ON target_document.id = target_version.document_id
                JOIN chunk source
                  ON source.chunk_hash = target.chunk_hash
                 AND source.chunk_policy_version = target.chunk_policy_version
                JOIN chunk_embedding source_embedding ON source_embedding.chunk_id = source.id
                WHERE target_document.knowledge_base_id = ?
                  AND target_document.status = 'ACTIVE'
                  AND target_document.current_version_id = target_version.id
                  AND target_version.status = 'PUBLISHED'
                  AND target.chunk_type = 'CHILD'
                  AND target.enabled = true
                  AND target.chunk_policy_version = ?
                  AND source_embedding.model_id = ?
                  AND source_embedding.model_version = ?
                  AND source_embedding.dimension = ?
                ON CONFLICT (chunk_id, index_generation_id) DO NOTHING
                """, context.generationId(), context.knowledgeBaseId(), context.chunkPolicyVersion(),
                context.model().modelId(), context.model().modelVersion(), context.model().dimension());
    }

    private List<Record> missing(RebuildContext context, int limit) {
        return dsl.fetch("""
                SELECT c.id, c.embedding_text, c.chunk_hash
                FROM chunk c
                JOIN document_version dv ON dv.id = c.document_version_id
                JOIN document d ON d.id = dv.document_id
                LEFT JOIN chunk_embedding ce
                  ON ce.chunk_id = c.id AND ce.index_generation_id = ?
                WHERE d.knowledge_base_id = ?
                  AND d.status = 'ACTIVE'
                  AND d.current_version_id = dv.id
                  AND dv.status = 'PUBLISHED'
                  AND c.chunk_type = 'CHILD'
                  AND c.enabled = true
                  AND c.chunk_policy_version = ?
                  AND ce.id IS NULL
                ORDER BY d.id, c.order_index
                LIMIT ?
                """, context.generationId(), context.knowledgeBaseId(), context.chunkPolicyVersion(), limit);
    }

    private void activate(RebuildContext context) {
        var lease = dsl.fetchOne("SELECT status, attempt FROM index_rebuild_job WHERE id = ? FOR UPDATE",
                context.jobId());
        if (!"RUNNING".equals(lease.get("status", String.class))
                || lease.get("attempt", Integer.class) != context.attempt()) {
            throw new IllegalStateException("Index rebuild lease was superseded by recovery");
        }
        if (hasActiveIngestion(context.knowledgeBaseId())) {
            throw new IllegalStateException("Index activation is waiting for active ingestion jobs");
        }
        var expected = targetChunkCount(context);
        var actual = coveredChunkCount(context);
        if (actual != expected) {
            throw new IllegalStateException("Index rebuild vector count does not match the target Chunk count");
        }
        dsl.execute("""
                UPDATE index_generation
                SET status = 'RETIRED', retired_at = now()
                WHERE knowledge_base_id = ? AND status = 'ACTIVE' AND id <> ?
                """, context.knowledgeBaseId(), context.generationId());
        dsl.execute("""
                UPDATE index_generation
                SET status = 'ACTIVE', activated_at = now(), retired_at = NULL
                WHERE id = ? AND status = 'BUILDING'
                """, context.generationId());
        dsl.execute("""
                UPDATE index_rebuild_job
                SET status = 'SUCCEEDED', total_chunks = ?, completed_chunks = ?, failed_chunks = 0,
                    completed_at = now(), next_attempt_at = now(), updated_at = now(), error_message = NULL
                WHERE id = ?
                """, expected, actual, context.jobId());
    }

    private int targetChunkCount(RebuildContext context) {
        return dsl.fetchCount(dsl.selectOne()
                .from("chunk c")
                .join("document_version dv").on("dv.id = c.document_version_id")
                .join("document d").on("d.id = dv.document_id")
                .where(org.jooq.impl.DSL.field("d.knowledge_base_id").eq(context.knowledgeBaseId()))
                .and("d.status = 'ACTIVE'")
                .and("d.current_version_id = dv.id")
                .and("dv.status = 'PUBLISHED'")
                .and("c.chunk_type = 'CHILD'")
                .and("c.enabled = true")
                .and("c.chunk_policy_version = ?", context.chunkPolicyVersion()));
    }

    private int coveredChunkCount(RebuildContext context) {
        return dsl.fetchCount(dsl.selectOne()
                .from("chunk_embedding ce")
                .join("chunk c").on("c.id = ce.chunk_id")
                .join("document_version dv").on("dv.id = c.document_version_id")
                .join("document d").on("d.id = dv.document_id")
                .where(org.jooq.impl.DSL.field("ce.index_generation_id").eq(context.generationId()))
                .and("d.knowledge_base_id = ?", context.knowledgeBaseId())
                .and("d.status = 'ACTIVE'")
                .and("d.current_version_id = dv.id")
                .and("dv.status = 'PUBLISHED'")
                .and("c.chunk_type = 'CHILD'")
                .and("c.enabled = true")
                .and("c.chunk_policy_version = ?", context.chunkPolicyVersion()));
    }

    private boolean hasActiveIngestion(UUID knowledgeBaseId) {
        return dsl.fetchExists(dsl.selectOne().from("ingestion_job")
                .where(org.jooq.impl.DSL.field("knowledge_base_id").eq(knowledgeBaseId))
                .and(org.jooq.impl.DSL.field("status").in("PENDING", "RUNNING")));
    }

    private void fail(RebuildContext context, RuntimeException exception) {
        transactions.executeWithoutResult(ignored -> {
            var job = dsl.fetchOne("""
                    SELECT status, attempt, max_attempts, total_chunks, completed_chunks
                    FROM index_rebuild_job WHERE id = ? FOR UPDATE
                    """, context.jobId());
            var attempt = job.get("attempt", Integer.class);
            if (!"RUNNING".equals(job.get("status", String.class)) || attempt != context.attempt()) return;
            var retry = attempt < job.get("max_attempts", Integer.class);
            if (!retry) {
                dsl.execute("UPDATE index_generation SET status = 'FAILED' WHERE id = ? AND status = 'BUILDING'",
                        context.generationId());
            }
            dsl.execute("""
                    UPDATE index_rebuild_job
                    SET status = ?, failed_chunks = GREATEST(total_chunks - completed_chunks, 0),
                        error_message = ?,
                        next_attempt_at = CASE WHEN ? THEN now() + (? * interval '5 seconds') ELSE now() END,
                        completed_at = CASE WHEN ? THEN NULL ELSE now() END,
                        updated_at = now()
                    WHERE id = ?
                    """, retry ? "QUEUED" : "FAILED", safeMessage(exception), retry, attempt, retry,
                    context.jobId());
        });
    }

    private String safeMessage(RuntimeException exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.substring(0, Math.min(1000, message.length()));
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record RebuildContext(
            UUID jobId,
            UUID knowledgeBaseId,
            UUID generationId,
            int attempt,
            EmbeddingModelReference model,
            String chunkPolicyVersion
    ) {
    }
}
