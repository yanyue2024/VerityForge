package com.yanyue.rag.worker.governance;

import com.yanyue.rag.domain.port.ObjectStoragePort;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SupersededVersionCleanup {
    private static final Logger log = LoggerFactory.getLogger(SupersededVersionCleanup.class);

    private final DSLContext dsl;
    private final ObjectStoragePort storage;
    private final int retentionDays;
    private final int batchSize;

    public SupersededVersionCleanup(
            DSLContext dsl,
            ObjectStoragePort storage,
            @Value("${rag.governance.version-retention-days:30}") int retentionDays,
            @Value("${rag.governance.cleanup-batch-size:50}") int batchSize
    ) {
        this.dsl = dsl;
        this.storage = storage;
        this.retentionDays = Math.max(1, retentionDays);
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(
            initialDelayString = "${rag.governance.cleanup-initial-delay-ms:120000}",
            fixedDelayString = "${rag.governance.cleanup-delay-ms:86400000}"
    )
    public void cleanRetainedVersions() {
        var candidates = dsl.fetch("""
                SELECT dv.id, dv.search_index_cleaned_at IS NOT NULL AS search_index_cleaned
                FROM document_version dv
                JOIN document d ON d.id = dv.document_id
                WHERE dv.status IN ('SUPERSEDED', 'EXPIRED')
                  AND dv.content_cleaned_at IS NULL
                  AND dv.updated_at < now() - (? * interval '1 day')
                  AND (d.current_version_id IS DISTINCT FROM dv.id OR d.status <> 'ACTIVE')
                ORDER BY dv.updated_at
                LIMIT ?
                """, retentionDays, batchSize);

        for (var candidate : candidates) {
            var versionId = candidate.get("id", UUID.class);
            try {
                if (!Boolean.TRUE.equals(candidate.get("search_index_cleaned", Boolean.class))) {
                    cleanSearchIndex(versionId);
                }
                if (hasHistoricalReferences(dsl, versionId)) {
                    continue;
                }
                cleanPhysicalContent(versionId);
            } catch (RuntimeException exception) {
                log.warn("Deferred cleanup failed for document version {}", versionId, exception);
            }
        }
    }

    private void cleanSearchIndex(UUID versionId) {
        dsl.transaction(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("""
                    DELETE FROM chunk_embedding ce
                    USING chunk c
                    WHERE ce.chunk_id = c.id AND c.document_version_id = ?
                    """, versionId);
            tx.execute("""
                    UPDATE document_version
                    SET search_index_cleaned_at = COALESCE(search_index_cleaned_at, now())
                    WHERE id = ? AND status IN ('SUPERSEDED', 'EXPIRED')
                    """, versionId);
        });
    }

    private void cleanPhysicalContent(UUID versionId) {
        var objectKeys = dsl.fetch("""
                SELECT object_key FROM document_asset WHERE document_version_id = ? ORDER BY object_key
                """, versionId).getValues("object_key", String.class);
        for (var objectKey : objectKeys) {
            storage.deleteObject(objectKey);
        }

        dsl.transaction(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var eligible = tx.fetchOptional("""
                    SELECT dv.id
                    FROM document_version dv
                    JOIN document d ON d.id = dv.document_id
                    WHERE dv.id = ?
                      AND dv.status IN ('SUPERSEDED', 'EXPIRED')
                      AND dv.content_cleaned_at IS NULL
                      AND (d.current_version_id IS DISTINCT FROM dv.id OR d.status <> 'ACTIVE')
                    FOR UPDATE OF dv
                    """, versionId).isPresent();
            if (!eligible || hasHistoricalReferences(tx, versionId)) {
                return;
            }
            tx.execute("""
                    DELETE FROM ingestion_artifact
                    WHERE job_id IN (SELECT id FROM ingestion_job WHERE document_version_id = ?)
                    """, versionId);
            tx.execute("DELETE FROM chunk WHERE document_version_id = ?", versionId);
            tx.execute("DELETE FROM document_block WHERE document_version_id = ?", versionId);
            tx.execute("DELETE FROM document_asset WHERE document_version_id = ?", versionId);
            tx.execute("""
                    UPDATE document_version
                    SET search_index_cleaned_at = COALESCE(search_index_cleaned_at, now()),
                        content_cleaned_at = now()
                    WHERE id = ?
                    """, versionId);
        });
    }

    private boolean hasHistoricalReferences(DSLContext context, UUID versionId) {
        return Boolean.TRUE.equals(context.fetchValue("""
                SELECT EXISTS (
                    SELECT 1 FROM citation WHERE document_version_id = ?
                    UNION ALL
                    SELECT 1 FROM evidence_item WHERE document_version_id = ?
                    UNION ALL
                    SELECT 1
                    FROM retrieval_candidate rc
                    JOIN chunk c ON c.id = rc.chunk_id
                    WHERE c.document_version_id = ?
                )
                """, versionId, versionId, versionId, Boolean.class));
    }
}
