package com.yanyue.rag.infrastructure.persistence;

import com.yanyue.rag.domain.port.CitationValidationPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqCitationValidationAdapter implements CitationValidationPort {
    private final DSLContext dsl;

    public JooqCitationValidationAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean isCurrentlyValid(UUID organizationId, UUID userId, RetrievalHit hit, Instant at) {
        var checkedAt = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        return dsl.fetchOptional("""
                SELECT 1
                FROM chunk c
                JOIN document_version dv ON dv.id = c.document_version_id
                JOIN document d ON d.id = dv.document_id
                JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
                WHERE c.id = ? AND c.document_version_id = ? AND d.id = ?
                  AND kb.organization_id = ?
                  AND d.status = 'ACTIVE'
                  AND d.current_version_id = dv.id
                  AND dv.status = 'PUBLISHED'
                  AND c.enabled = true
                  AND document_is_accessible(d.id, ?)
                  AND (dv.valid_from IS NULL OR dv.valid_from <= ?::timestamptz)
                  AND (dv.valid_to IS NULL OR dv.valid_to > ?::timestamptz)
                """, hit.chunkId(), hit.documentVersionId(), hit.documentId(), organizationId,
                userId, checkedAt, checkedAt)
                .isPresent();
    }
}
