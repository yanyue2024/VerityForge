package com.yanyue.rag.api.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.knowledge.DocumentUploadService;
import com.yanyue.rag.contract.knowledge.CompleteUploadResponse;
import com.yanyue.rag.contract.knowledge.CreateUploadIntentRequest;
import com.yanyue.rag.contract.knowledge.DocumentAccessPolicyView;
import com.yanyue.rag.contract.knowledge.UploadIntentResponse;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {
    private final DocumentUploadService uploads;
    private final DocumentAccessService access;
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final S3Presigner presigner;
    private final String bucket;

    public DocumentController(DocumentUploadService uploads, DocumentAccessService access,
                              DSLContext dsl, ObjectMapper objectMapper, S3Presigner presigner,
                              @Value("${rag.storage.bucket:rag-assets}") String bucket) {
        this.uploads = uploads;
        this.access = access;
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents/upload-intents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public UploadIntentResponse uploadIntent(@AuthenticationPrincipal AuthenticatedUser user,
                                             @PathVariable UUID knowledgeBaseId,
                                             @Valid @RequestBody CreateUploadIntentRequest request) {
        return uploads.initiate(user.organizationId(), user.userId(), knowledgeBaseId, request);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public CompleteUploadResponse complete(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable UUID uploadId) {
        return uploads.complete(user.organizationId(), user.userId(), uploadId);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public List<DocumentRow> documents(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID knowledgeBaseId) {
        return dsl.fetch("""
                SELECT d.id, d.title, d.status, d.current_version_id, d.access_mode, d.updated_at,
                       dv.version_number, dv.status AS version_status, dv.valid_from, dv.valid_to,
                       dv.source_name, dv.source_type, COALESCE(dv.metadata, '{}'::jsonb)::text AS metadata,
                       dv.parse_quality_status, asset.content_type, asset.byte_size,
                       job.status AS ingestion_status, job.current_stage AS ingestion_current_stage,
                       COALESCE((SELECT count(*) FROM chunk c WHERE c.document_version_id = dv.id AND c.chunk_type = 'CHILD'), 0) AS chunk_count,
                       COALESCE((SELECT count(*) FROM chunk c WHERE c.document_version_id = dv.id AND c.chunk_type = 'PARENT'), 0) AS parent_chunk_count
                FROM document d
                LEFT JOIN LATERAL (
                    SELECT candidate.*
                    FROM document_version candidate
                    WHERE candidate.document_id = d.id
                    ORDER BY candidate.version_number DESC
                    LIMIT 1
                ) dv ON true
                LEFT JOIN LATERAL (
                    SELECT candidate.status, candidate.current_stage
                    FROM ingestion_job candidate
                    WHERE candidate.document_version_id = dv.id
                    ORDER BY candidate.created_at DESC
                    LIMIT 1
                ) job ON true
                LEFT JOIN LATERAL (
                    SELECT candidate.content_type, candidate.byte_size
                    FROM document_asset candidate
                    WHERE candidate.document_version_id = dv.id
                    ORDER BY candidate.created_at DESC
                    LIMIT 1
                ) asset ON true
                WHERE d.knowledge_base_id = ? AND d.organization_id = ? AND d.status <> 'DELETED'
                  AND document_is_accessible(d.id, ?)
                ORDER BY d.updated_at DESC
                """, knowledgeBaseId, user.organizationId(), user.userId()).map(record -> new DocumentRow(
                record.get("id", UUID.class), record.get("title", String.class), record.get("status", String.class),
                record.get("current_version_id", UUID.class), record.get("version_number", Integer.class),
                record.get("version_status", String.class), instant(record.get("valid_from", OffsetDateTime.class)),
                instant(record.get("valid_to", OffsetDateTime.class)), record.get("chunk_count", Long.class),
                record.get("parent_chunk_count", Long.class), record.get("access_mode", String.class),
                record.get("source_name", String.class), record.get("source_type", String.class),
                record.get("content_type", String.class), record.get("byte_size", Long.class),
                record.get("metadata", String.class), record.get("parse_quality_status", String.class),
                record.get("ingestion_status", String.class), record.get("ingestion_current_stage", String.class),
                instant(record.get("updated_at", OffsetDateTime.class))));
    }

    @GetMapping("/documents/{documentId}")
    public DocumentDetail document(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID documentId) {
        var document = dsl.fetchOptional("""
                SELECT id, knowledge_base_id, title, status, current_version_id, created_at, updated_at
                FROM document WHERE id = ? AND organization_id = ? AND document_is_accessible(id, ?)
                """, documentId, user.organizationId(), user.userId())
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        var versions = dsl.fetch("""
                SELECT dv.id, dv.version_number, dv.source_name, dv.source_type, dv.status, dv.valid_from, dv.valid_to,
                       dv.published_at, dv.metadata::text AS metadata, dv.created_at,
                       dv.parser_name, dv.parser_version, dv.parse_quality_status, dv.parse_quality_score,
                       dv.parse_quality_report::text AS parse_quality_report,
                       job.id AS ingestion_job_id, job.status AS ingestion_status
                FROM document_version dv
                LEFT JOIN LATERAL (
                    SELECT candidate.id, candidate.status
                    FROM ingestion_job candidate
                    WHERE candidate.document_version_id = dv.id
                    ORDER BY candidate.created_at DESC
                    LIMIT 1
                ) job ON true
                WHERE dv.document_id = ? ORDER BY dv.version_number DESC
                """, documentId).map(record -> new VersionRow(
                record.get("id", UUID.class), record.get("version_number", Integer.class),
                record.get("source_name", String.class), record.get("source_type", String.class),
                record.get("status", String.class), instant(record.get("valid_from", OffsetDateTime.class)),
                instant(record.get("valid_to", OffsetDateTime.class)), instant(record.get("published_at", OffsetDateTime.class)),
                record.get("metadata", String.class), record.get("ingestion_job_id", UUID.class),
                record.get("ingestion_status", String.class), record.get("parser_name", String.class),
                record.get("parser_version", String.class), record.get("parse_quality_status", String.class),
                record.get("parse_quality_score", Integer.class), record.get("parse_quality_report", String.class),
                instant(record.get("created_at", OffsetDateTime.class))));
        return new DocumentDetail(document.get("id", UUID.class), document.get("knowledge_base_id", UUID.class),
                document.get("title", String.class), document.get("status", String.class),
                document.get("current_version_id", UUID.class),
                access.view(user.organizationId(), user.userId(), documentId), versions,
                instant(document.get("created_at", OffsetDateTime.class)),
                instant(document.get("updated_at", OffsetDateTime.class)));
    }

    @GetMapping("/document-versions/{versionId}/chunks")
    public List<ChunkRow> chunks(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID versionId) {
        return dsl.fetch("""
                SELECT c.id, c.parent_chunk_id, c.chunk_type, c.order_index, c.chunk_text, c.render_markdown,
                       c.context_header, c.estimated_tokens, c.tokenizer_name, c.token_count_method,
                       c.source_mapping_status, c.source_block_ids, c.enabled,
                       db.page_number, db.attributes::text AS source_attributes,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                               'start', segment.chunk_local_start,
                               'end', segment.chunk_local_end,
                               'type', source.block_type,
                               'attributes', source.attributes
                           ) ORDER BY segment.segment_order)
                           FROM chunk_source_segment segment
                           JOIN document_block source ON source.id = segment.document_block_id
                           WHERE segment.chunk_id = c.id
                       ), '[]'::jsonb)::text AS source_segments
                FROM chunk c
                JOIN document_version dv ON dv.id = c.document_version_id
                JOIN document d ON d.id = dv.document_id
                LEFT JOIN document_block db ON db.id = c.source_block_ids[1]
                WHERE c.document_version_id = ? AND d.organization_id = ?
                  AND document_is_accessible(d.id, ?)
                ORDER BY c.chunk_type DESC, c.order_index LIMIT 5000
                """, versionId, user.organizationId(), user.userId()).map(record -> new ChunkRow(
                record.get("id", UUID.class), record.get("parent_chunk_id", UUID.class),
                record.get("chunk_type", String.class), record.get("order_index", Integer.class),
                record.get("chunk_text", String.class), record.get("context_header", String.class),
                record.get("estimated_tokens", Integer.class), record.get("tokenizer_name", String.class),
                record.get("token_count_method", String.class), record.get("source_mapping_status", String.class),
                sourceLocation(record.get("page_number", Integer.class), record.get("source_attributes", String.class)),
                record.get("source_block_ids", UUID[].class) == null ? List.of()
                        : List.of(record.get("source_block_ids", UUID[].class)),
                persistedRenderMarkdown(record.get("render_markdown", String.class),
                        record.get("chunk_text", String.class), record.get("source_segments", String.class)),
                record.get("enabled", Boolean.class)));
    }

    private String persistedRenderMarkdown(String persisted, String chunkText, String segmentsJson) {
        return persisted == null || persisted.isBlank()
                ? renderChunkMarkdown(chunkText, segmentsJson)
                : persisted;
    }

    @GetMapping("/document-versions/{versionId}/content")
    public DocumentContent content(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable UUID versionId) {
        var allowed = dsl.fetchOptional("""
                SELECT 1 FROM document_version dv
                JOIN document d ON d.id = dv.document_id
                WHERE dv.id = ? AND d.organization_id = ? AND document_is_accessible(d.id, ?)
                """, versionId, user.organizationId(), user.userId()).isPresent();
        if (!allowed) throw new IllegalArgumentException("Document version not found");
        var version = dsl.fetchOne("""
                SELECT normalized_markdown, parse_quality_status, parse_quality_score,
                       parse_quality_report::text AS parse_quality_report
                FROM document_version WHERE id = ?
                """, versionId);
        var total = dsl.fetchOne(
                "SELECT count(*) FROM document_block WHERE document_version_id = ?", versionId)
                .get(0, Integer.class);
        var blocks = dsl.fetch("""
                SELECT id, block_type, order_index, block_text, page_number,
                       heading_path::text AS heading_path, bounding_box::text AS bounding_box,
                       source_start, source_end, source_offset_unit, attributes::text AS attributes
                FROM document_block
                WHERE document_version_id = ?
                ORDER BY order_index
                LIMIT 5000
                """, versionId).map(record -> new ContentBlock(
                record.get("id", UUID.class), record.get("block_type", String.class),
                record.get("order_index", Integer.class), record.get("block_text", String.class),
                record.get("page_number", Integer.class), record.get("heading_path", String.class),
                record.get("bounding_box", String.class), record.get("source_start", Integer.class),
                record.get("source_end", Integer.class), record.get("source_offset_unit", String.class),
                record.get("attributes", String.class)));
        return new DocumentContent(versionId, total, version.get("normalized_markdown", String.class),
                version.get("parse_quality_status", String.class), version.get("parse_quality_score", Integer.class),
                version.get("parse_quality_report", String.class), blocks);
    }

    @GetMapping("/document-versions/{versionId}/asset")
    public AssetView asset(@AuthenticationPrincipal AuthenticatedUser user,
                           @PathVariable UUID versionId) {
        return dsl.fetchOptional("""
                SELECT asset.object_key, asset.file_name, asset.content_type, asset.byte_size, asset.file_hash, asset.created_at
                FROM document_asset asset
                JOIN document_version dv ON dv.id = asset.document_version_id
                JOIN document d ON d.id = dv.document_id
                WHERE asset.document_version_id = ? AND d.organization_id = ?
                  AND document_is_accessible(d.id, ?)
                ORDER BY asset.created_at DESC LIMIT 1
                """, versionId, user.organizationId(), user.userId()).map(record -> {
                    var lifetime = Duration.ofMinutes(10);
                    var object = GetObjectRequest.builder().bucket(bucket)
                            .key(record.get("object_key", String.class)).responseContentDisposition("inline")
                            .responseContentType(record.get("content_type", String.class)).build();
                    var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(lifetime).getObjectRequest(object).build());
                    return new AssetView(record.get("file_name", String.class),
                            record.get("content_type", String.class), record.get("byte_size", Long.class),
                            record.get("file_hash", String.class), signed.url().toString(),
                            java.time.Instant.now().plus(lifetime), instant(record.get("created_at", OffsetDateTime.class)));
                })
                .orElseThrow(() -> new IllegalArgumentException("Document asset not found"));
    }

    @GetMapping("/document-versions/{versionId}/metadata-revisions")
    public List<MetadataRevisionRow> metadataRevisions(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @PathVariable UUID versionId) {
        return dsl.fetch("""
                SELECT revision.id, revision.document_version_id, revision.new_metadata,
                       revision.new_valid_from, revision.new_valid_to, revision.created_at,
                       actor.display_name AS changed_by
                FROM document_metadata_revision revision
                JOIN document_version dv ON dv.id = revision.document_version_id
                JOIN document d ON d.id = dv.document_id
                LEFT JOIN app_user actor ON actor.id = revision.changed_by
                WHERE revision.document_version_id = ? AND d.organization_id = ?
                  AND document_is_accessible(d.id, ?)
                ORDER BY revision.created_at DESC
                """, versionId, user.organizationId(), user.userId()).map(record -> new MetadataRevisionRow(
                record.get("id", UUID.class), record.get("document_version_id", UUID.class),
                metadata(record.get("new_metadata", JSONB.class)),
                instant(record.get("new_valid_from", OffsetDateTime.class)),
                instant(record.get("new_valid_to", OffsetDateTime.class)),
                record.get("changed_by", String.class), instant(record.get("created_at", OffsetDateTime.class))));
    }

    @GetMapping("/documents/{documentId}/version-diff")
    public VersionDiff versionDiff(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID documentId,
            @RequestParam UUID fromVersionId,
            @RequestParam UUID toVersionId
    ) {
        if (fromVersionId.equals(toVersionId)) {
            throw new IllegalArgumentException("Version diff requires two different versions");
        }
        var versions = dsl.fetch("""
                SELECT dv.id, dv.version_number, dv.metadata::text AS metadata, dv.valid_from, dv.valid_to
                FROM document_version dv
                JOIN document d ON d.id = dv.document_id
                WHERE d.id = ? AND d.organization_id = ? AND document_is_accessible(d.id, ?)
                  AND dv.id IN (?, ?)
                """, documentId, user.organizationId(), user.userId(), fromVersionId, toVersionId);
        if (versions.size() != 2) throw new IllegalArgumentException("Document versions not found");
        var fromVersion = versions.stream().filter(record -> fromVersionId.equals(record.get("id", UUID.class)))
                .findFirst().orElseThrow();
        var toVersion = versions.stream().filter(record -> toVersionId.equals(record.get("id", UUID.class)))
                .findFirst().orElseThrow();
        var fromBlocks = blocksForDiff(fromVersionId);
        var toBlocks = blocksForDiff(toVersionId);
        var fromHashes = fromBlocks.stream().map(DiffBlock::hash).collect(java.util.stream.Collectors.toSet());
        var toHashes = toBlocks.stream().map(DiffBlock::hash).collect(java.util.stream.Collectors.toSet());
        var fromByOrder = fromBlocks.stream().collect(java.util.stream.Collectors.toMap(
                DiffBlock::orderIndex, value -> value));
        var toByOrder = toBlocks.stream().collect(java.util.stream.Collectors.toMap(
                DiffBlock::orderIndex, value -> value));
        var modifiedOrders = new HashSet<Integer>();
        var entries = new java.util.ArrayList<VersionDiffEntry>();
        int unchanged = 0;
        int added = 0;
        int modified = 0;
        int removed = 0;
        for (var block : toBlocks) {
            if (fromHashes.contains(block.hash())) {
                unchanged++;
                continue;
            }
            var previous = fromByOrder.get(block.orderIndex());
            if (previous != null && !toHashes.contains(previous.hash())) {
                modified++;
                modifiedOrders.add(block.orderIndex());
                entries.add(new VersionDiffEntry("MODIFIED", block.orderIndex(), previous.pageNumber(),
                        block.pageNumber(), preview(previous.text()), preview(block.text())));
            } else {
                added++;
                entries.add(new VersionDiffEntry("ADDED", block.orderIndex(), null,
                        block.pageNumber(), null, preview(block.text())));
            }
        }
        for (var block : fromBlocks) {
            if (toHashes.contains(block.hash()) || modifiedOrders.contains(block.orderIndex())) continue;
            if (toByOrder.containsKey(block.orderIndex())) continue;
            removed++;
            entries.add(new VersionDiffEntry("REMOVED", block.orderIndex(), block.pageNumber(),
                    null, preview(block.text()), null));
        }
        entries.sort(java.util.Comparator.comparingInt(VersionDiffEntry::orderIndex));
        return new VersionDiff(
                documentId, fromVersionId, fromVersion.get("version_number", Integer.class),
                toVersionId, toVersion.get("version_number", Integer.class), unchanged, added, modified, removed,
                !java.util.Objects.equals(fromVersion.get("metadata", String.class), toVersion.get("metadata", String.class)),
                !java.util.Objects.equals(fromVersion.get("valid_from", OffsetDateTime.class),
                        toVersion.get("valid_from", OffsetDateTime.class))
                        || !java.util.Objects.equals(fromVersion.get("valid_to", OffsetDateTime.class),
                        toVersion.get("valid_to", OffsetDateTime.class)),
                entries.stream().limit(100).toList()
        );
    }

    private List<DiffBlock> blocksForDiff(UUID versionId) {
        return dsl.fetch("""
                SELECT order_index, page_number, block_text, block_hash
                FROM document_block WHERE document_version_id = ? ORDER BY order_index
                """, versionId).map(record -> new DiffBlock(
                record.get("order_index", Integer.class), record.get("page_number", Integer.class),
                record.get("block_text", String.class), record.get("block_hash", String.class).strip()));
    }

    private String preview(String text) {
        if (text == null) return null;
        var normalized = text.strip();
        return normalized.length() <= 1_200 ? normalized : normalized.substring(0, 1_200);
    }

    @GetMapping("/ingestion-jobs/{jobId}")
    public JobDetail job(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID jobId) {
        var job = dsl.fetchOptional("""
                SELECT id, status, current_stage, attempt, max_attempts, error_message, created_at, started_at, completed_at
                FROM ingestion_job job
                WHERE id = ? AND organization_id = ?
                  AND document_is_accessible(job.document_id, ?)
                """, jobId, user.organizationId(), user.userId())
                .orElseThrow(() -> new IllegalArgumentException("Job not found"));
        var stages = dsl.fetch("""
                SELECT stage, status, attempt, metrics::text AS metrics, error_message, started_at, completed_at
                FROM ingestion_job_stage WHERE job_id = ? ORDER BY
                    CASE stage WHEN 'PARSE' THEN 1 WHEN 'NORMALIZE' THEN 2 WHEN 'CHUNK' THEN 3 WHEN 'EMBED' THEN 4 ELSE 5 END
                """, jobId).map(record -> new StageRow(record.get("stage", String.class), record.get("status", String.class),
                record.get("attempt", Integer.class), record.get("metrics", String.class),
                record.get("error_message", String.class), instant(record.get("started_at", OffsetDateTime.class)),
                instant(record.get("completed_at", OffsetDateTime.class))));
        return new JobDetail(job.get("id", UUID.class), job.get("status", String.class),
                job.get("current_stage", String.class), job.get("attempt", Integer.class),
                job.get("max_attempts", Integer.class), job.get("error_message", String.class), stages,
                instant(job.get("created_at", OffsetDateTime.class)), instant(job.get("started_at", OffsetDateTime.class)),
                instant(job.get("completed_at", OffsetDateTime.class)));
    }

    @PatchMapping("/documents/{documentId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public void status(@AuthenticationPrincipal AuthenticatedUser user,
                       @PathVariable UUID documentId,
                       @Valid @RequestBody DocumentStatusRequest request) {
        var target = request.status().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ACTIVE", "INACTIVE").contains(target)) {
            throw new IllegalArgumentException("Document status must be ACTIVE or INACTIVE");
        }
        var updated = dsl.execute("""
                UPDATE document SET status = ?, updated_at = now()
                WHERE id = ? AND organization_id = ? AND status <> 'DELETED'
                  AND document_is_accessible(id, ?)
                """, target, documentId, user.organizationId(), user.userId());
        if (updated == 0) throw new IllegalArgumentException("Document not found");
    }

    @PostMapping("/ingestion-jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public CompleteUploadResponse retry(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID jobId) {
        return retry(user, jobId, null);
    }

    @PostMapping("/ingestion-jobs/{jobId}/retry-with-parser")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public CompleteUploadResponse retry(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID jobId,
            @RequestBody(required = false) RetryIngestionRequest request
    ) {
        var profile = request == null || request.parserProfile() == null
                ? "AUTO" : request.parserProfile().strip().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("AUTO", "LIGHTWEIGHT", "DOCLING").contains(profile)) {
            throw new IllegalArgumentException("Parser profile must be AUTO, LIGHTWEIGHT, or DOCLING");
        }
        var options = request == null || request.options() == null ? java.util.Map.of() : request.options();
        var updated = dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var job = tx.fetchOptional("""
                    SELECT job.id, job.attempt, job.max_attempts FROM ingestion_job job
                    WHERE job.id = ? AND job.organization_id = ? AND job.status IN ('FAILED', 'AWAITING_REVIEW')
                      AND document_is_accessible(job.document_id, ?)
                    FOR UPDATE
                    """, jobId, user.organizationId(), user.userId()).orElse(null);
            if (job == null) return 0;
            tx.execute("""
                    UPDATE ingestion_job
                    SET status = 'PENDING', attempt = 0, heartbeat_at = NULL,
                        error_code = NULL, error_message = NULL, completed_at = NULL,
                        parser_profile = ?, parser_options = ?::jsonb,
                        quality_approved_at = NULL, quality_approved_by = NULL
                    WHERE id = ?
                    """, profile, json(options), jobId);
            tx.execute("""
                    UPDATE document_version
                    SET status = 'PROCESSING', updated_at = now()
                    WHERE id = (SELECT document_version_id FROM ingestion_job WHERE id = ?)
                      AND status IN ('FAILED', 'REVIEW_REQUIRED')
                    """, jobId);
            tx.execute("""
                    UPDATE ingestion_job_stage
                    SET status = 'PENDING', input_hash = NULL, output_hash = NULL, metrics = '{}',
                        error_message = NULL, started_at = NULL, completed_at = NULL
                    WHERE job_id = ?
                    """, jobId);
            tx.execute("""
                    INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
                    VALUES ('IngestionJob', ?, 'ingestion.manual-retry', jsonb_build_object('jobId', ?::text))
                    """, jobId, jobId.toString());
            return 1;
        });
        if (updated == 0) throw new IllegalArgumentException("Failed ingestion job not found");
        return new CompleteUploadResponse(jobId, "PENDING");
    }

    @PostMapping("/ingestion-jobs/{jobId}/approve-quality")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    public CompleteUploadResponse approveQuality(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID jobId
    ) {
        var updated = dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var job = tx.fetchOptional("""
                    SELECT job.id FROM ingestion_job job
                    JOIN document_version dv ON dv.id = job.document_version_id
                    WHERE job.id = ? AND job.organization_id = ? AND job.status = 'AWAITING_REVIEW'
                      AND dv.parse_quality_status = 'WARNING' AND document_is_accessible(job.document_id, ?)
                    FOR UPDATE OF job
                    """, jobId, user.organizationId(), user.userId()).orElse(null);
            if (job == null) return 0;
            tx.execute("""
                    UPDATE ingestion_job
                    SET status = 'PENDING', attempt = 0, error_message = NULL,
                        quality_approved_at = now(), quality_approved_by = ?
                    WHERE id = ?
                    """, user.userId(), jobId);
            tx.execute("""
                    UPDATE document_version SET status = 'PROCESSING', updated_at = now()
                    WHERE id = (SELECT document_version_id FROM ingestion_job WHERE id = ?)
                    """, jobId);
            tx.execute("""
                    UPDATE ingestion_job_stage SET status = 'PENDING', error_message = NULL, completed_at = NULL
                    WHERE job_id = ? AND stage = 'QUALITY'
                    """, jobId);
            tx.execute("""
                    INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
                    VALUES ('IngestionJob', ?, 'ingestion.quality-approved', jsonb_build_object('jobId', ?::text))
                    """, jobId, jobId.toString());
            return 1;
        });
        if (updated == 0) throw new IllegalArgumentException("Reviewable ingestion job not found");
        return new CompleteUploadResponse(jobId, "PENDING");
    }

    private java.time.Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    private java.util.Map<String, Object> metadata(JSONB value) {
        if (value == null || value.data() == null) return java.util.Map.of();
        try {
            return objectMapper.readValue(value.data(), new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid document metadata", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize request", exception);
        }
    }

    private String sourceLocation(Integer pageNumber, String attributesJson) {
        var values = java.util.Map.<String, Object>of();
        if (attributesJson != null) {
            try {
                values = objectMapper.readValue(attributesJson, new TypeReference<>() { });
            } catch (JsonProcessingException ignored) {
                values = java.util.Map.of();
            }
        }
        if (values.get("sheetName") != null) {
            return values.get("sheetName") + (values.get("cellRange") == null ? "" : " · " + values.get("cellRange"));
        }
        if (pageNumber != null) return "第 " + pageNumber + " 页";
        if (values.get("lineStart") != null) {
            return "第 " + values.get("lineStart") + "–" + values.getOrDefault("lineEnd", values.get("lineStart")) + " 行";
        }
        if (values.get("paragraphIndex") != null) return "第 " + values.get("paragraphIndex") + " 段";
        return "原文位置可用";
    }

    String renderChunkMarkdown(String chunkText, String segmentsJson) {
        if (chunkText == null || chunkText.isBlank() || segmentsJson == null) return chunkText;
        List<ChunkDisplaySegment> segments;
        try {
            segments = objectMapper.readValue(segmentsJson, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return chunkText;
        }
        if (segments.isEmpty()) return chunkText;
        var rendered = new java.util.ArrayList<String>();
        for (var segment : segments) {
            if (segment.start() == null || segment.end() == null
                    || segment.start() < 0 || segment.end() > chunkText.length()
                    || segment.start() >= segment.end()) {
                return chunkText;
            }
            var value = chunkText.substring(segment.start(), segment.end()).strip();
            if (value.isBlank()) continue;
            var attributes = segment.attributes() == null ? java.util.Map.<String, Object>of() : segment.attributes();
            var type = segment.type() == null ? "PARAGRAPH" : segment.type();
            String markdown;
            if ("CODE".equals(type)) {
                var language = java.util.Objects.toString(attributes.get("language"), "").strip();
                markdown = "```" + language + "\n" + value + "\n```";
            } else if ("HEADING".equals(type) || "TITLE".equals(type)) {
                var level = "TITLE".equals(type) ? 2 : headingLevel(attributes.get("headingLevel"));
                markdown = "#".repeat(level) + " " + value;
            } else if ("LIST".equals(type)) {
                markdown = value.replaceAll("(?m)^\\s*•\\s*", "- ");
            } else {
                markdown = value;
            }
            if (attributes.get("admonition") != null) {
                markdown = markdown.lines().map(line -> "> " + line)
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
            rendered.add(markdown);
        }
        return rendered.isEmpty() ? chunkText : String.join("\n\n", rendered);
    }

    private int headingLevel(Object value) {
        try {
            return Math.max(3, Math.min(6, Integer.parseInt(java.util.Objects.toString(value, "3")) + 1));
        } catch (NumberFormatException ignored) {
            return 3;
        }
    }

    public record DocumentRow(UUID id, String title, String status, UUID currentVersionId, Integer versionNumber,
                              String versionStatus, java.time.Instant validFrom, java.time.Instant validTo,
                              Long chunkCount, Long parentChunkCount, String accessMode, String sourceName,
                              String sourceType, String contentType, Long byteSize, String metadata,
                              String parseQualityStatus, String ingestionStatus, String ingestionCurrentStage,
                              java.time.Instant updatedAt) { }
    public record VersionRow(UUID id, Integer versionNumber, String sourceName, String sourceType, String status,
                             java.time.Instant validFrom, java.time.Instant validTo, java.time.Instant publishedAt,
                             String metadata, UUID ingestionJobId, String ingestionStatus,
                             String parserName, String parserVersion, String parseQualityStatus,
                             Integer parseQualityScore, String parseQualityReport,
                             java.time.Instant createdAt) { }
    public record DocumentDetail(UUID id, UUID knowledgeBaseId, String title, String status, UUID currentVersionId,
                                 DocumentAccessPolicyView accessPolicy, List<VersionRow> versions,
                                 java.time.Instant createdAt, java.time.Instant updatedAt) { }
    public record ChunkRow(UUID id, UUID parentChunkId, String type, Integer orderIndex, String text,
                           String contextHeader, Integer estimatedTokens, String tokenizerName,
                           String tokenCountMethod, String sourceMappingStatus, String sourceLocation,
                           List<UUID> sourceBlockIds, String renderedMarkdown, Boolean enabled) { }
    public record DocumentContent(UUID documentVersionId, int totalBlocks, String normalizedMarkdown,
                                  String parseQualityStatus, Integer parseQualityScore,
                                  String parseQualityReport, List<ContentBlock> blocks) { }
    public record ContentBlock(UUID id, String type, Integer orderIndex, String text, Integer pageNumber,
                               String headingPath, String boundingBox, Integer sourceStart, Integer sourceEnd,
                               String sourceOffsetUnit, String attributes) { }
    public record AssetView(String fileName, String contentType, Long byteSize, String fileHash, String previewUrl,
                            java.time.Instant previewExpiresAt,
                            java.time.Instant createdAt) { }
    public record MetadataRevisionRow(UUID revisionId, UUID documentVersionId, java.util.Map<String, Object> metadata,
                                      java.time.Instant validFrom, java.time.Instant validTo, String changedBy,
                                      java.time.Instant createdAt) { }
    public record JobDetail(UUID id, String status, String currentStage, Integer attempt, Integer maxAttempts,
                            String errorMessage, List<StageRow> stages, java.time.Instant createdAt,
                            java.time.Instant startedAt, java.time.Instant completedAt) { }
    public record StageRow(String stage, String status, Integer attempt, String metrics, String errorMessage,
                           java.time.Instant startedAt, java.time.Instant completedAt) { }
    public record DocumentStatusRequest(@jakarta.validation.constraints.NotBlank String status) { }
    public record RetryIngestionRequest(String parserProfile, java.util.Map<String, Object> options) { }
    private record ChunkDisplaySegment(Integer start, Integer end, String type,
                                       java.util.Map<String, Object> attributes) { }
    private record DiffBlock(Integer orderIndex, Integer pageNumber, String text, String hash) { }
    public record VersionDiff(
            UUID documentId,
            UUID fromVersionId,
            Integer fromVersionNumber,
            UUID toVersionId,
            Integer toVersionNumber,
            int unchangedBlocks,
            int addedBlocks,
            int modifiedBlocks,
            int removedBlocks,
            boolean metadataChanged,
            boolean validityChanged,
            List<VersionDiffEntry> entries
    ) { }
    public record VersionDiffEntry(
            String changeType,
            int orderIndex,
            Integer beforePage,
            Integer afterPage,
            String beforeText,
            String afterText
    ) { }
}
