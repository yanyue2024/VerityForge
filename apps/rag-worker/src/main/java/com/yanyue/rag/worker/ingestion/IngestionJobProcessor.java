package com.yanyue.rag.worker.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.parser.NormalizedDocument;
import com.yanyue.rag.contract.parser.ParseQualityStatus;
import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.domain.chunking.AdaptiveParentChildChunker;
import com.yanyue.rag.domain.chunking.ChunkQualityAssessor;
import com.yanyue.rag.domain.chunking.ChunkQualityStatus;
import com.yanyue.rag.domain.chunking.v4.ChunkSourceMap;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.SourceMapStatus;
import com.yanyue.rag.domain.knowledge.Chunk;
import com.yanyue.rag.domain.knowledge.ChunkPolicy;
import com.yanyue.rag.domain.knowledge.ChunkType;
import com.yanyue.rag.domain.knowledge.DocumentBlock;
import com.yanyue.rag.domain.model.EmbeddingModelReference;
import com.yanyue.rag.domain.port.EmbeddingModelPort;
import com.yanyue.rag.infrastructure.retrieval.PgVectorFormatter;
import com.yanyue.rag.worker.parser.DocumentParsingService;
import com.yanyue.rag.worker.storage.StoredDocumentReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestionJobProcessor {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final StoredDocumentReader reader;
    private final DocumentParsingService parsing;
    private final EmbeddingModelPort embeddings;
    private final int embeddingBatchSize;
    private final long heartbeatIntervalSeconds;
    private final boolean normalizedArtifactReuseEnabled;
    private final RagTelemetry telemetry;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("ingestion-heartbeat-", 0).factory());
    private final AdaptiveParentChildChunker chunker = new AdaptiveParentChildChunker();
    private final ChunkQualityAssessor chunkQualityAssessor = new ChunkQualityAssessor();

    public IngestionJobProcessor(
            DSLContext dsl,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            StoredDocumentReader reader,
            DocumentParsingService parsing,
            EmbeddingModelPort embeddings,
            @Value("${rag.ingestion.embedding-batch-size:32}") int embeddingBatchSize,
            @Value("${rag.ingestion.heartbeat-interval-seconds:30}") long heartbeatIntervalSeconds,
            @Value("${rag.ingestion.normalized-artifact-reuse-enabled:true}") boolean normalizedArtifactReuseEnabled,
            RagTelemetry telemetry
    ) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.reader = reader;
        this.parsing = parsing;
        this.embeddings = embeddings;
        this.embeddingBatchSize = Math.max(1, embeddingBatchSize);
        this.heartbeatIntervalSeconds = Math.max(1, heartbeatIntervalSeconds);
        this.normalizedArtifactReuseEnabled = normalizedArtifactReuseEnabled;
        this.telemetry = telemetry;
    }

    public void process(UUID jobId) {
        var context = transactions.execute(status -> claim(jobId));
        if (context == null) return;
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> heartbeat(context), heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
        try {
            runStage(context, "PARSE", () -> parse(context));
            runStage(context, "NORMALIZE", () -> normalize(context));
            runStage(context, "QUALITY", () -> quality(context));
            runStage(context, "CHUNK", () -> chunk(context));
            if (requiresQualityReview(context)) {
                pauseForQualityReview(context);
                return;
            }
            runStage(context, "EMBED", () -> embed(context));
            runStage(context, "PUBLISH", () -> publish(context));
            transactions.executeWithoutResult(status -> {
                assertLease(context);
                dsl.execute("""
                        UPDATE ingestion_job
                        SET status = 'SUCCEEDED', current_stage = 'PUBLISH', heartbeat_at = NULL,
                            completed_at = now(), error_code = NULL, error_message = NULL
                        WHERE id = ?
                        """, jobId);
            });
        } catch (LeaseLostException exception) {
            telemetry.increment("rag.ingestion.lease.lost", Map.of());
        } catch (RuntimeException exception) {
            fail(context, exception);
        } finally {
            heartbeat.cancel(false);
        }
    }

    @PreDestroy
    void closeHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    private IngestionJobContext claim(UUID jobId) {
        var record = dsl.fetchOptional("""
                SELECT j.id, j.organization_id, j.knowledge_base_id, j.document_id, j.document_version_id,
                       j.status, j.attempt, j.max_attempts, a.object_key, a.file_name, a.content_type,
                       a.file_hash, j.parser_profile, j.parser_options::text AS parser_options,
                       kb.chunk_policy::text AS chunk_policy
                FROM ingestion_job j
                JOIN document_asset a ON a.document_version_id = j.document_version_id
                JOIN knowledge_base kb ON kb.id = j.knowledge_base_id
                WHERE j.id = ?
                FOR UPDATE OF j
                """, jobId).orElse(null);
        if (record == null) return null;
        var status = record.get("status", String.class);
        var attempt = record.get("attempt", Integer.class);
        var maximum = record.get("max_attempts", Integer.class);
        if ("SUCCEEDED".equals(status) || "CANCELLED".equals(status) || "RUNNING".equals(status)
                || "AWAITING_REVIEW".equals(status)
                || attempt >= maximum) {
            return null;
        }
        dsl.execute("""
                UPDATE ingestion_job
                SET status = 'RUNNING', attempt = attempt + 1, started_at = now(),
                    heartbeat_at = now(), completed_at = NULL, error_code = NULL, error_message = NULL
                WHERE id = ?
                """, jobId);
        return new IngestionJobContext(
                jobId,
                attempt + 1,
                record.get("organization_id", UUID.class),
                record.get("knowledge_base_id", UUID.class),
                record.get("document_id", UUID.class),
                record.get("document_version_id", UUID.class),
                record.get("object_key", String.class),
                record.get("file_name", String.class),
                record.get("content_type", String.class),
                record.get("file_hash", String.class),
                record.get("parser_profile", String.class),
                read(record.get("parser_options", String.class), new TypeReference<Map<String, Object>>() { }),
                read(record.get("chunk_policy", String.class), ChunkPolicy.class)
        );
    }

    private void runStage(IngestionJobContext context, String stage, StageAction action) {
        var status = dsl.fetchOptional("""
                SELECT status FROM ingestion_job_stage WHERE job_id = ? AND stage = ?
                """, context.jobId(), stage)
                .map(record -> record.get(0, String.class))
                .orElseThrow(() -> new IllegalStateException("Ingestion stage is missing: " + stage));
        if ("SUCCEEDED".equals(status)) return;
        transactions.executeWithoutResult(ignored -> {
            assertLease(context);
            dsl.execute("""
                    UPDATE ingestion_job_stage
                    SET status = 'RUNNING', attempt = attempt + 1, started_at = now(),
                        completed_at = NULL, error_message = NULL
                    WHERE job_id = ? AND stage = ?
                    """, context.jobId(), stage);
            dsl.execute("UPDATE ingestion_job SET current_stage = ? WHERE id = ?", stage, context.jobId());
        });
        try {
            var result = telemetry.observe("rag.ingestion.stage", Map.of("stage", stage), action::run);
            transactions.executeWithoutResult(ignored -> {
                assertLease(context);
                dsl.execute("""
                        UPDATE ingestion_job_stage
                        SET status = 'SUCCEEDED', input_hash = ?, output_hash = ?, metrics = ?::jsonb,
                            completed_at = now(), error_message = NULL
                        WHERE job_id = ? AND stage = ?
                        """, result.inputHash(), result.outputHash(), json(result.metrics()), context.jobId(), stage);
            });
        } catch (LeaseLostException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(ignored -> {
                assertLease(context);
                dsl.execute("""
                        UPDATE ingestion_job_stage
                        SET status = 'FAILED', completed_at = now(), error_message = ?
                        WHERE job_id = ? AND stage = ?
                        """, message(exception), context.jobId(), stage);
            });
            throw exception;
        }
    }

    private StageResult parse(IngestionJobContext context) {
        var bytes = reader.read(context.objectKey());
        var fileHash = sha256(bytes);
        if (context.declaredSha256() != null && !context.declaredSha256().equals("0".repeat(64))
                && !context.declaredSha256().equalsIgnoreCase(fileHash)) {
            throw new IllegalArgumentException("Uploaded object SHA-256 does not match the upload intent");
        }
        var parserIdentity = normalizedArtifactReuseEnabled ? parsing.identity() : null;
        var reusable = reusableNormalizedArtifact(context, fileHash, parserIdentity);
        var normalized = reusable == null
                ? parsing.parse(context.objectKey(), context.fileName(), context.contentType(), bytes,
                        context.parserProfile(), context.parserOptions())
                : read(reusable.payload(), NormalizedDocument.class);
        if (normalized.blocks().isEmpty()) {
            throw new IllegalStateException("Parser returned no document blocks");
        }
        var payload = json(normalized);
        transactions.executeWithoutResult(ignored -> {
            assertLease(context);
            dsl.execute("""
                    INSERT INTO ingestion_artifact (job_id, artifact_type, payload, artifact_hash)
                    VALUES (?, 'NORMALIZED_DOCUMENT', ?::jsonb, ?)
                    ON CONFLICT (job_id, artifact_type) DO UPDATE
                    SET payload = EXCLUDED.payload, artifact_hash = EXCLUDED.artifact_hash, updated_at = now()
                    """, context.jobId(), payload, sha256(payload));
            dsl.execute("UPDATE document_asset SET file_hash = ? WHERE document_version_id = ?",
                    fileHash, context.documentVersionId());
            dsl.execute("""
                    UPDATE document_version
                    SET content_hash = ?, source_type = ?, updated_at = now()
                    WHERE id = ?
                    """, fileHash, sourceType(context.fileName()), context.documentVersionId());
        });
        return new StageResult(fileHash, sha256(payload), Map.of(
                "parser", normalized.parserName(),
                "parserVersion", normalized.parserVersion(),
                "blocks", normalized.blocks().size(),
                "bytes", bytes.length,
                "normalizedArtifactReused", reusable != null,
                "reusedFromVersionId", reusable == null ? "" : reusable.versionId().toString()
        ));
    }

    private StageResult normalize(IngestionJobContext context) {
        var document = normalized(context.jobId());
        var blockFingerprints = document.blocks().stream()
                .map(block -> new Fingerprint(Integer.toString(block.orderIndex()), sha256(block.text())))
                .toList();
        var previousVersionId = comparisonVersion(context);
        var previousFingerprints = previousVersionId == null ? List.<Fingerprint>of() : dsl.fetch("""
                SELECT order_index, block_hash FROM document_block
                WHERE document_version_id = ? ORDER BY order_index
                """, previousVersionId).map(record -> new Fingerprint(
                Integer.toString(record.get("order_index", Integer.class)),
                record.get("block_hash", String.class).strip()));
        var diff = diff(previousFingerprints, blockFingerprints);
        transactions.executeWithoutResult(ignored -> {
            assertLease(context);
            dsl.execute("DELETE FROM document_block WHERE document_version_id = ?", context.documentVersionId());
            for (var block : document.blocks()) {
                var blockHash = sha256(block.text());
                var blockId = stableId(context.documentVersionId(), "block:" + block.orderIndex() + ":" + blockHash);
                dsl.execute("""
                        INSERT INTO document_block
                            (id, document_version_id, block_type, order_index, block_text, page_number,
                             heading_path, bounding_box, source_start, source_end, source_offset_unit,
                             block_hash, attributes)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?::jsonb)
                        """, blockId, context.documentVersionId(), block.type().name(), block.orderIndex(), block.text(),
                        block.pageNumber(), json(block.headingPath()), json(block.boundingBox()), block.sourceStart(),
                        block.sourceEnd(), block.sourceOffsetUnit(), blockHash, json(block.attributes()));
            }
            dsl.execute("""
                    UPDATE document_version
                    SET normalized_markdown = ?, normalized_content_hash = ?, parser_name = ?, parser_version = ?,
                        parser_schema_version = ?, parse_quality_status = ?, parse_quality_score = ?,
                        parse_quality_report = ?::jsonb, updated_at = now()
                    WHERE id = ?
                    """, document.normalizedMarkdown(), sha256(document.normalizedMarkdown()), document.parserName(),
                    document.parserVersion(), document.schemaVersion(), document.quality().status().name(),
                    document.quality().score(), json(document.quality()), context.documentVersionId());
        });
        return StageResult.of(sha256(json(document.blocks())), Map.of(
                "blocks", document.blocks().size(),
                "unchanged", diff.unchanged(),
                "modified", diff.modified(),
                "added", diff.added(),
                "removed", diff.removed()
        ));
    }

    private StageResult quality(IngestionJobContext context) {
        var report = normalized(context.jobId()).quality();
        if (report.status() == ParseQualityStatus.FAIL) {
            throw new QualityGateFailedException("Document parsing failed the quality gate: "
                    + report.issues().stream().map(issue -> issue.message()).findFirst().orElse("unknown reason"));
        }
        return StageResult.of(sha256(json(report)), Map.of(
                "status", report.status().name(),
                "score", report.score(),
                "issues", report.issues().size(),
                "metrics", report.metrics()
        ));
    }

    private StageResult chunk(IngestionJobContext context) {
        var blocks = loadBlocks(context.documentVersionId());
        var chunking = chunker.chunkWithSourceMaps(context.documentVersionId(), blocks, context.chunkPolicy());
        var chunks = chunking.chunks();
        var sourceMaps = chunking.sourceMaps();
        var quality = chunkQualityAssessor.assess(chunking, blocks, context.chunkPolicy());
        var qualityPayload = json(quality);
        transactions.executeWithoutResult(ignored -> {
            assertLease(context);
            dsl.execute("""
                    INSERT INTO ingestion_artifact (job_id, artifact_type, payload, artifact_hash)
                    VALUES (?, 'CHUNK_QUALITY_REPORT', ?::jsonb, ?)
                    ON CONFLICT (job_id, artifact_type) DO UPDATE
                    SET payload = EXCLUDED.payload, artifact_hash = EXCLUDED.artifact_hash, updated_at = now()
                    """, context.jobId(), qualityPayload, sha256(qualityPayload));
        });
        if (quality.status() == ChunkQualityStatus.FAIL) {
            throw new QualityGateFailedException("Document chunking failed the quality gate: "
                    + quality.issues().stream()
                            .filter(issue -> issue.severity() == ChunkQualityStatus.FAIL)
                            .map(issue -> issue.message()).findFirst().orElse("unknown reason"));
        }
        var previousVersionId = comparisonVersion(context);
        var previousFingerprints = previousVersionId == null ? List.<Fingerprint>of() : dsl.fetch("""
                SELECT chunk_type, order_index, chunk_hash FROM chunk
                WHERE document_version_id = ? AND chunk_policy_version = ?
                ORDER BY chunk_type, order_index
                """, previousVersionId, context.chunkPolicy().version()).map(record -> new Fingerprint(
                record.get("chunk_type", String.class) + ":" + record.get("order_index", Integer.class),
                record.get("chunk_hash", String.class).strip()));
        var chunkFingerprints = chunks.stream().map(value -> new Fingerprint(
                value.type().name() + ":" + value.orderIndex(), value.chunkHash())).toList();
        var diff = diff(previousFingerprints, chunkFingerprints);
        transactions.executeWithoutResult(ignored -> {
            assertLease(context);
            dsl.execute("DELETE FROM chunk WHERE document_version_id = ?", context.documentVersionId());
            chunks.stream().filter(value -> value.type() == ChunkType.PARENT).forEach(this::insertChunk);
            chunks.stream().filter(value -> value.type() == ChunkType.CHILD).forEach(this::insertChunk);
            sourceMaps.forEach(this::insertSourceMap);
        });
        long parents = chunks.stream().filter(value -> value.type() == ChunkType.PARENT).count();
        long children = chunks.size() - parents;
        var metrics = new java.util.LinkedHashMap<String, Object>();
        metrics.put("parents", parents);
        metrics.put("children", children);
        metrics.put("policy", context.chunkPolicy().version());
        metrics.put("qualityStatus", quality.status().name());
        metrics.put("qualityScore", quality.score());
        metrics.put("qualityIssues", quality.issues().size());
        metrics.put("quality", quality.metrics());
        metrics.put("unchanged", diff.unchanged());
        metrics.put("modified", diff.modified());
        metrics.put("added", diff.added());
        metrics.put("removed", diff.removed());
        return StageResult.of(sha256(json(chunks.stream().map(Chunk::chunkHash).toList())), metrics);
    }

    private StageResult embed(IngestionJobContext context) {
        var generation = transactions.execute(ignored -> {
            assertLease(context);
            return activeGeneration(context);
        });
        var generationId = generation.id();
        var reused = transactions.execute(ignored -> {
            assertLease(context);
            return dsl.execute("""
                    INSERT INTO chunk_embedding
                        (chunk_id, index_generation_id, model_id, model_version, dimension, embedding, embedding_hash)
                    SELECT target.id, ?, source_embedding.model_id, source_embedding.model_version,
                           source_embedding.dimension, source_embedding.embedding, source_embedding.embedding_hash
                    FROM chunk target
                    JOIN chunk source
                      ON source.chunk_hash = target.chunk_hash
                     AND source.chunk_policy_version = target.chunk_policy_version
                     AND source.id <> target.id
                    JOIN chunk_embedding source_embedding
                      ON source_embedding.chunk_id = source.id
                     AND source_embedding.index_generation_id = ?
                    WHERE target.document_version_id = ? AND target.chunk_type = 'CHILD'
                    ON CONFLICT (chunk_id, index_generation_id) DO NOTHING
                    """, generationId, generationId, context.documentVersionId());
        });

        var missing = dsl.fetch("""
                SELECT c.id, c.embedding_text, c.chunk_hash
                FROM chunk c
                LEFT JOIN chunk_embedding ce ON ce.chunk_id = c.id AND ce.index_generation_id = ?
                WHERE c.document_version_id = ? AND c.chunk_type = 'CHILD' AND ce.id IS NULL
                ORDER BY c.order_index
                """, generationId, context.documentVersionId());
        for (int offset = 0; offset < missing.size(); offset += embeddingBatchSize) {
            var batch = missing.subList(offset, Math.min(missing.size(), offset + embeddingBatchSize));
            var vectors = embeddings.embed(generation.model(),
                    batch.stream().map(record -> record.get("embedding_text", String.class)).toList());
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("Embedding model returned an incomplete ingestion batch");
            }
            transactions.executeWithoutResult(ignored -> {
                assertLease(context);
                for (int index = 0; index < batch.size(); index++) {
                    var record = batch.get(index);
                    dsl.execute("""
                            INSERT INTO chunk_embedding
                                (chunk_id, index_generation_id, model_id, model_version,
                                 dimension, embedding, embedding_hash)
                            VALUES (?, ?, ?, ?, ?, ?::vector, ?)
                            ON CONFLICT (chunk_id, index_generation_id) DO NOTHING
                            """, record.get("id", UUID.class), generationId, generation.model().modelId(),
                            generation.model().modelVersion(), generation.model().dimension(),
                            PgVectorFormatter.format(vectors.get(index)),
                            sha256(generation.model().modelId() + ":" + generation.model().modelVersion() + ":"
                                    + record.get("chunk_hash", String.class)));
                }
            });
        }
        transactions.executeWithoutResult(ignored -> {
            assertLease(context);
            dsl.execute("""
                    UPDATE document_version SET status = 'READY', updated_at = now()
                    WHERE id = ? AND status = 'PROCESSING'
                    """, context.documentVersionId());
        });
        return StageResult.of(generationId.toString(),
                Map.of("generationId", generationId, "reused", reused, "created", missing.size()));
    }

    private StageResult publish(IngestionJobContext context) {
        transactions.executeWithoutResult(ignored -> {
            assertLease(context);
            var document = dsl.fetchOne("""
                    SELECT current_version_id FROM document WHERE id = ? FOR UPDATE
                    """, context.documentId());
            var current = document.get("current_version_id", UUID.class);
            var nextStatus = dsl.fetchOptional("SELECT status FROM document_version WHERE id = ? FOR UPDATE",
                            context.documentVersionId())
                    .map(record -> record.get(0, String.class))
                    .orElseThrow(() -> new IllegalStateException("Document version is missing"));
            if (context.documentVersionId().equals(current) && "PUBLISHED".equals(nextStatus)) return;
            if (!"READY".equals(nextStatus) && !"PUBLISHED".equals(nextStatus)) {
                throw new IllegalStateException("Only a ready document version can be published");
            }
            if (current != null && !current.equals(context.documentVersionId())) {
                dsl.execute("""
                        UPDATE document_version
                        SET status = 'SUPERSEDED', updated_at = now()
                        WHERE id = ? AND status = 'PUBLISHED'
                        """, current);
            }
            dsl.execute("""
                    UPDATE document_version
                    SET status = 'PUBLISHED', published_at = COALESCE(published_at, now()), updated_at = now()
                    WHERE id = ?
                    """, context.documentVersionId());
            dsl.execute("""
                    UPDATE document
                    SET current_version_id = ?, status = 'ACTIVE', updated_at = now()
                    WHERE id = ?
                    """, context.documentVersionId(), context.documentId());
        });
        return StageResult.of(context.documentVersionId().toString(),
                Map.of("documentId", context.documentId(), "versionId", context.documentVersionId()));
    }

    private ActiveGeneration activeGeneration(IngestionJobContext context) {
        var existing = dsl.fetchOptional("""
                SELECT id, embedding_profile_id, embedding_model_id, embedding_model_version, embedding_dimension
                FROM index_generation
                WHERE knowledge_base_id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """, context.knowledgeBaseId());
        if (existing.isPresent()) return generation(existing.get());
        var id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO index_generation
                    (id, knowledge_base_id, generation_number, status, embedding_model_id,
                     embedding_model_version, embedding_dimension, chunk_policy_version, activated_at)
                VALUES (?, ?, COALESCE((SELECT max(generation_number) + 1 FROM index_generation
                                       WHERE knowledge_base_id = ?), 1),
                        'ACTIVE', 'deterministic-local', 'v1', ?, ?, now())
                """, id, context.knowledgeBaseId(), context.knowledgeBaseId(), 384,
                context.chunkPolicy().version());
        return new ActiveGeneration(id,
                new EmbeddingModelReference(null, "deterministic-local", "v1", 384));
    }

    private ActiveGeneration generation(Record record) {
        return new ActiveGeneration(record.get("id", UUID.class), new EmbeddingModelReference(
                record.get("embedding_profile_id", UUID.class),
                record.get("embedding_model_id", String.class),
                record.get("embedding_model_version", String.class),
                record.get("embedding_dimension", Integer.class)
        ));
    }

    private List<DocumentBlock> loadBlocks(UUID versionId) {
        return dsl.fetch("""
                SELECT id, block_type, order_index, block_text, page_number, heading_path::text AS heading_path,
                       source_start, source_end, source_offset_unit, block_hash, attributes::text AS attributes
                FROM document_block WHERE document_version_id = ? ORDER BY order_index
                """, versionId).map(record -> new DocumentBlock(
                record.get("id", UUID.class),
                versionId,
                com.yanyue.rag.contract.parser.BlockType.valueOf(record.get("block_type", String.class)),
                record.get("order_index", Integer.class),
                record.get("block_text", String.class),
                record.get("page_number", Integer.class),
                read(record.get("heading_path", String.class), new TypeReference<List<String>>() { }),
                record.get("source_start", Integer.class),
                record.get("source_end", Integer.class),
                OffsetUnit.valueOf(record.get("source_offset_unit", String.class)),
                record.get("block_hash", String.class),
                read(record.get("attributes", String.class), new TypeReference<Map<String, Object>>() { })
        ));
    }

    private void insertChunk(Chunk chunk) {
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, parent_chunk_id, chunk_type, order_index, chunk_text,
                     render_markdown, context_header, embedding_text, estimated_tokens, tokenizer_name,
                     token_count_method, source_block_ids, chunk_hash, chunk_policy_version, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, chunk.id(), chunk.documentVersionId(), chunk.parentChunkId(), chunk.type().name(),
                chunk.orderIndex(), chunk.text(), chunk.renderMarkdown(), chunk.contextHeader(), chunk.embeddingText(),
                chunk.estimatedTokens(), chunk.tokenizerName(), chunk.tokenCountMethod(),
                chunk.sourceBlockIds().toArray(UUID[]::new), chunk.chunkHash(), chunk.chunkPolicyVersion(), chunk.enabled());
    }

    private void insertSourceMap(UUID chunkId, ChunkSourceMap sourceMap) {
        dsl.execute("""
                UPDATE chunk
                SET source_mapping_status = ?, source_mapping_failure_reason = ?
                WHERE id = ?
                """, sourceMap.status().name(),
                sourceMap.status() == SourceMapStatus.MAPPED ? null : sourceMap.failureReason().name(), chunkId);
        for (var segment : sourceMap.segments()) {
            dsl.execute("""
                    INSERT INTO chunk_source_segment
                        (chunk_id, segment_order, chunk_local_start, chunk_local_end,
                         document_block_id, block_local_start, block_local_end,
                         document_source_start, document_source_end, document_offset_unit)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, chunkId, segment.segmentOrder(), segment.chunkLocalStart(), segment.chunkLocalEnd(),
                    segment.documentBlockId(), segment.blockLocalStart(), segment.blockLocalEnd(),
                    segment.documentSourceStart(), segment.documentSourceEnd(),
                    segment.documentOffsetUnit() == null ? null : segment.documentOffsetUnit().name());
        }
    }

    private NormalizedDocument normalized(UUID jobId) {
        var payload = dsl.fetchOptional("""
                SELECT payload::text FROM ingestion_artifact
                WHERE job_id = ? AND artifact_type = 'NORMALIZED_DOCUMENT'
                """, jobId).map(record -> record.get(0, String.class)).orElse(null);
        if (payload == null) throw new IllegalStateException("Normalized parser artifact is missing");
        return read(payload, NormalizedDocument.class);
    }

    private ReusableArtifact reusableNormalizedArtifact(
            IngestionJobContext context,
            String fileHash,
            DocumentParsingService.ParserIdentity parserIdentity
    ) {
        if (!normalizedArtifactReuseEnabled || parserIdentity == null) return null;
        var parserProfile = context.parserProfile() == null || context.parserProfile().isBlank()
                ? "AUTO" : context.parserProfile().strip().toUpperCase(java.util.Locale.ROOT);
        var parserOptions = DocumentParsingService.effectiveOptions(context.parserOptions());
        return dsl.fetchOptional("""
                SELECT dv.id AS version_id, ia.payload::text AS payload
                FROM document_version dv
                JOIN ingestion_job job ON job.document_version_id = dv.id AND job.status = 'SUCCEEDED'
                JOIN ingestion_artifact ia ON ia.job_id = job.id
                    AND ia.artifact_type = 'NORMALIZED_DOCUMENT'
                WHERE dv.document_id = ? AND dv.id <> ? AND dv.content_hash = ?
                  AND ia.payload ->> 'sourceName' = ?
                  AND ia.payload ->> 'parserName' = ?
                  AND ia.payload ->> 'parserVersion' = ?
                  AND ia.payload ->> 'schemaVersion' = ?
                  AND COALESCE(ia.payload #>> '{metadata,requestedContentType}', '') = ?
                  AND COALESCE(ia.payload #>> '{metadata,parserProfile}', 'AUTO') = ?
                  AND COALESCE(ia.payload #> '{metadata,parserOptions}', '{}'::jsonb) = ?::jsonb
                ORDER BY dv.version_number DESC, job.created_at DESC
                LIMIT 1
                """, context.documentId(), context.documentVersionId(), fileHash, context.fileName(),
                parserIdentity.name(), parserIdentity.version(), parserIdentity.schemaVersion(),
                context.contentType() == null ? "" : context.contentType(), parserProfile, json(parserOptions))
                .map(record -> new ReusableArtifact(
                record.get("version_id", UUID.class), record.get("payload", String.class))).orElse(null);
    }

    private boolean requiresQualityReview(IngestionJobContext context) {
        return dsl.fetchOptional("""
                SELECT 1 FROM document_version dv
                JOIN ingestion_job job ON job.document_version_id = dv.id
                WHERE job.id = ? AND dv.parse_quality_status = 'WARNING' AND job.quality_approved_at IS NULL
                """, context.jobId()).isPresent();
    }

    private void pauseForQualityReview(IngestionJobContext context) {
        transactions.executeWithoutResult(ignored -> {
            assertLease(context);
            dsl.execute("""
                    UPDATE ingestion_job_stage SET status = 'REVIEW_REQUIRED', completed_at = now()
                    WHERE job_id = ? AND stage = 'QUALITY'
                    """, context.jobId());
            dsl.execute("""
                    UPDATE ingestion_job
                    SET status = 'AWAITING_REVIEW', current_stage = 'QUALITY', heartbeat_at = NULL,
                        completed_at = NULL, error_code = NULL, error_message = NULL
                    WHERE id = ?
                    """, context.jobId());
            dsl.execute("""
                    UPDATE document_version SET status = 'REVIEW_REQUIRED', updated_at = now()
                    WHERE id = ? AND status = 'PROCESSING'
                    """, context.documentVersionId());
        });
    }

    private UUID comparisonVersion(IngestionJobContext context) {
        return dsl.fetchOptional("""
                SELECT id FROM document_version
                WHERE document_id = ? AND id <> ? AND status IN ('PUBLISHED', 'SUPERSEDED')
                ORDER BY version_number DESC LIMIT 1
                """, context.documentId(), context.documentVersionId())
                .map(record -> record.get("id", UUID.class)).orElse(null);
    }

    private DiffStats diff(List<Fingerprint> previous, List<Fingerprint> current) {
        var previousHashes = previous.stream().map(Fingerprint::hash).collect(java.util.stream.Collectors.toSet());
        var currentHashes = current.stream().map(Fingerprint::hash).collect(java.util.stream.Collectors.toSet());
        var previousPositions = previous.stream().collect(java.util.stream.Collectors.toMap(
                Fingerprint::position, value -> value, (left, right) -> left));
        var currentPositions = current.stream().collect(java.util.stream.Collectors.toMap(
                Fingerprint::position, value -> value, (left, right) -> left));
        int unchanged = 0;
        int modified = 0;
        int added = 0;
        for (var value : current) {
            if (previousHashes.contains(value.hash())) unchanged++;
            else if (previousPositions.containsKey(value.position())) modified++;
            else added++;
        }
        int removed = 0;
        for (var value : previous) {
            if (!currentHashes.contains(value.hash()) && !currentPositions.containsKey(value.position())) removed++;
        }
        return new DiffStats(unchanged, modified, added, removed);
    }

    private void heartbeat(IngestionJobContext context) {
        try {
            int updated = dsl.execute("""
                    UPDATE ingestion_job SET heartbeat_at = now()
                    WHERE id = ? AND status = 'RUNNING' AND attempt = ?
                    """, context.jobId(), context.attempt());
            if (updated == 0) {
                telemetry.increment("rag.ingestion.heartbeat.rejected", Map.of());
            }
        } catch (RuntimeException exception) {
            telemetry.increment("rag.ingestion.heartbeat.error", Map.of(
                    "exception", exception.getClass().getSimpleName()));
        }
    }

    private void assertLease(IngestionJobContext context) {
        var job = dsl.fetchOptional("""
                SELECT status, attempt FROM ingestion_job WHERE id = ? FOR UPDATE
                """, context.jobId()).orElseThrow(() -> new LeaseLostException(context.jobId()));
        if (!"RUNNING".equals(job.get("status", String.class))
                || job.get("attempt", Integer.class) != context.attempt()) {
            throw new LeaseLostException(context.jobId());
        }
        dsl.execute("UPDATE ingestion_job SET heartbeat_at = now() WHERE id = ?", context.jobId());
    }

    private void fail(IngestionJobContext context, RuntimeException exception) {
        transactions.executeWithoutResult(ignored -> {
            var job = dsl.fetchOne("SELECT status, attempt, max_attempts, current_stage FROM ingestion_job WHERE id = ? FOR UPDATE",
                    context.jobId());
            if (job == null || job.get("attempt", Integer.class) != context.attempt()
                    || !"RUNNING".equals(job.get("status", String.class))) {
                return;
            }
            int attempt = job.get("attempt", Integer.class);
            int maximum = job.get("max_attempts", Integer.class);
            boolean retry = attempt < maximum && !(exception instanceof QualityGateFailedException);
            dsl.execute("""
                    UPDATE ingestion_job_stage
                    SET status = 'FAILED',
                        attempt = CASE WHEN status = 'PENDING' THEN attempt + 1 ELSE attempt END,
                        started_at = COALESCE(started_at, now()),
                        completed_at = now(),
                        error_message = ?
                    WHERE job_id = ? AND stage = ? AND status IN ('PENDING', 'RUNNING')
                    """, message(exception), context.jobId(), job.get("current_stage", String.class));
            dsl.execute("""
                    UPDATE ingestion_job
                    SET status = ?, heartbeat_at = NULL, error_code = ?, error_message = ?,
                        completed_at = CASE WHEN ? THEN NULL ELSE now() END
                    WHERE id = ?
                    """, retry ? "PENDING" : "FAILED", exception.getClass().getSimpleName(), message(exception),
                    retry, context.jobId());
            if (retry) {
                dsl.execute("""
                        INSERT INTO outbox_event
                            (aggregate_type, aggregate_id, event_type, payload, available_at)
                        VALUES ('IngestionJob', ?, 'ingestion.retry',
                                jsonb_build_object('jobId', ?::text, 'attempt', ?),
                                now() + (? * interval '5 seconds'))
                        """, context.jobId(), context.jobId().toString(), attempt, attempt);
            } else {
                dsl.execute("""
                        UPDATE document_version SET status = 'FAILED', updated_at = now()
                        WHERE id = ? AND status IN ('DRAFT', 'PROCESSING', 'READY')
                        """, context.documentVersionId());
            }
        });
    }

    private record ReusableArtifact(UUID versionId, String payload) { }
    private record Fingerprint(String position, String hash) { }
    private record DiffStats(int unchanged, int modified, int added, int removed) { }

    private String sourceType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "UNKNOWN" : fileName.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize ingestion value", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read ingestion value", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read ingestion value", exception);
        }
    }

    private static UUID stableId(UUID namespace, String value) {
        return UUID.nameUUIDFromBytes((namespace + ":" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String message(Throwable throwable) {
        var value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value.substring(0, Math.min(2000, value.length()));
    }

    @FunctionalInterface
    private interface StageAction {
        StageResult run();
    }

    private record ActiveGeneration(UUID id, EmbeddingModelReference model) {
    }

    private static final class LeaseLostException extends RuntimeException {
        private LeaseLostException(UUID jobId) {
            super("Ingestion job lease is no longer owned: " + jobId);
        }
    }

    private static final class QualityGateFailedException extends RuntimeException {
        private QualityGateFailedException(String message) {
            super(message);
        }
    }
}
