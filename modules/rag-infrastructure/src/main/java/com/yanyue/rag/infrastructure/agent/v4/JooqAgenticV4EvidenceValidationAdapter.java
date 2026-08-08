package com.yanyue.rag.infrastructure.agent.v4;

import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.chunking.v4.CandidateSpanBuilder;
import com.yanyue.rag.domain.port.AgenticV4EvidenceValidationPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqAgenticV4EvidenceValidationAdapter implements AgenticV4EvidenceValidationPort {
    private final DSLContext dsl;

    public JooqAgenticV4EvidenceValidationAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean isCurrentlyValid(
            UUID organizationId,
            UUID userId,
            AcceptedEvidence evidence,
            Instant at
    ) {
        var checkedAt = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        var parent = dsl.fetchOptional("""
                SELECT chunk.chunk_text
                FROM chunk
                JOIN document_version version ON version.id = chunk.document_version_id
                JOIN document ON document.id = version.document_id
                JOIN knowledge_base base ON base.id = document.knowledge_base_id
                WHERE chunk.id = ? AND chunk.document_version_id = ? AND document.id = ?
                  AND base.organization_id = ? AND document.status = 'ACTIVE'
                  AND document.current_version_id = version.id AND version.status = 'PUBLISHED'
                  AND chunk.enabled = true AND document_is_accessible(document.id, ?)
                  AND (version.valid_from IS NULL OR version.valid_from <= ?::timestamptz)
                  AND (version.valid_to IS NULL OR version.valid_to > ?::timestamptz)
                """, evidence.parentChunkId(), evidence.documentVersionId(), evidence.documentId(),
                organizationId, userId, checkedAt, checkedAt);
        if (parent.isEmpty()) return false;
        String parentText = parent.orElseThrow().get("chunk_text", String.class);
        var anchor = evidence.sourceAnchor();
        if (anchor.parentLocalEnd() > parentText.length()
                || !parentText.substring(anchor.parentLocalStart(), anchor.parentLocalEnd()).equals(evidence.quote())) {
            return false;
        }
        for (var segment : anchor.segments()) {
            var block = dsl.fetchOptional("""
                    SELECT block.block_text, block.source_start, block.source_end
                    FROM document_block block
                    WHERE block.id = ? AND block.document_version_id = ?
                    """, segment.documentBlockId(), evidence.documentVersionId());
            if (block.isEmpty()) return false;
            String blockText = block.orElseThrow().get("block_text", String.class);
            String anchoredParentText = parentText.substring(
                    segment.parentLocalStart(), segment.parentLocalEnd());
            if (segment.blockLocalEnd() > blockText.length()
                    || !blockText.substring(segment.blockLocalStart(), segment.blockLocalEnd())
                    .equals(anchoredParentText)) {
                return false;
            }
        }
        String expectedSpanId = CandidateSpanBuilder.stableSpanId(evidence.documentVersionId(),
                evidence.parentChunkId(), anchor.parentLocalStart(), anchor.parentLocalEnd(),
                CandidateSpanBuilder.textHash(parentText));
        return expectedSpanId.equals(evidence.spanId());
    }
}
