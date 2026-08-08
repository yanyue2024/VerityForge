package com.yanyue.rag.infrastructure.retrieval;

import com.yanyue.rag.domain.port.QuestionSuggestionContextPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqQuestionSuggestionContextAdapter implements QuestionSuggestionContextPort {
    private static final String FROM_EFFECTIVE_CHUNKS = """
            FROM chunk c
            JOIN document_version dv ON dv.id = c.document_version_id
            JOIN document d ON d.id = dv.document_id
            JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
            """;

    private final DSLContext dsl;
    private final RetrievalScopeSqlBuilder scopeBuilder = new RetrievalScopeSqlBuilder();

    public JooqQuestionSuggestionContextAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public SuggestionContext load(RetrievalScope scope, int maximumDocuments, int maximumExcerpts) {
        var scoped = scopeBuilder.build(scope);
        var snapshot = contentSnapshot(scoped);
        var excerpts = excerpts(scoped, Math.max(1, maximumDocuments), Math.max(1, maximumExcerpts));
        return new SuggestionContext(snapshot.revision(), excerpts);
    }

    @Override
    public EligibilitySnapshot eligibility(RetrievalScope scope) {
        var snapshot = contentSnapshot(scopeBuilder.build(scope));
        return new EligibilitySnapshot(snapshot.revision(), snapshot.documentVersionIds());
    }

    private ContentSnapshot contentSnapshot(ScopedSql scoped) {
        var sql = """
                SELECT kb.id::text AS knowledge_base_id,
                       kb.name,
                       kb.updated_at::text AS knowledge_base_updated_at,
                       d.id::text AS document_id,
                       d.updated_at::text AS document_updated_at,
                       d.access_mode,
                       d.allowed_roles::text AS allowed_roles,
                       d.allowed_user_ids::text AS allowed_user_ids,
                       dv.id::text AS document_version_id,
                       dv.updated_at::text AS document_version_updated_at,
                       dv.content_hash,
                       dv.metadata::text AS metadata,
                       count(c.id)::text AS chunk_count,
                       min(c.chunk_hash) AS first_chunk_hash,
                       max(c.chunk_hash) AS last_chunk_hash,
                       COALESCE((
                           SELECT generation.id::text
                           FROM index_generation generation
                           WHERE generation.knowledge_base_id = kb.id
                             AND generation.status = 'ACTIVE'
                           LIMIT 1
                       ), '') AS index_generation_id
                """ + FROM_EFFECTIVE_CHUNKS + """
                WHERE
                """ + scoped.predicate() + """
                GROUP BY kb.id, kb.name, kb.updated_at, d.id, d.updated_at, d.access_mode,
                         d.allowed_roles, d.allowed_user_ids, dv.id, dv.updated_at,
                         dv.content_hash, dv.metadata
                ORDER BY kb.id, d.id, dv.id
                """;
        var digestInput = new StringBuilder();
        var documentVersionIds = new LinkedHashSet<UUID>();
        for (var record : dsl.fetch(sql, scoped.parameters().toArray())) {
            documentVersionIds.add(UUID.fromString(record.get("document_version_id", String.class)));
            for (var field : List.of(
                    "knowledge_base_id", "name", "knowledge_base_updated_at", "document_id",
                    "document_updated_at", "access_mode", "allowed_roles", "allowed_user_ids",
                    "document_version_id", "document_version_updated_at", "content_hash", "metadata",
                    "chunk_count", "first_chunk_hash", "last_chunk_hash", "index_generation_id")) {
                digestInput.append(record.get(field, String.class)).append('\u001f');
            }
            digestInput.append('\n');
        }
        return new ContentSnapshot(sha256(digestInput.toString()), documentVersionIds);
    }

    private List<SourceExcerpt> excerpts(ScopedSql scoped, int maximumDocuments, int maximumExcerpts) {
        var sql = """
                WITH eligible AS (
                    SELECT kb.id AS knowledge_base_id,
                           kb.name AS knowledge_base_name,
                           d.id AS document_id,
                           dv.id AS document_version_id,
                           d.title AS document_title,
                           d.updated_at AS document_updated_at,
                           c.id AS chunk_id,
                           c.order_index,
                           c.chunk_text,
                           row_number() OVER (
                               PARTITION BY d.id
                               ORDER BY c.order_index, c.id
                           ) AS chunk_rank
                    """ + FROM_EFFECTIVE_CHUNKS + """
                    WHERE
                    """ + scoped.predicate() + """
                ), document_heads AS (
                    SELECT eligible.*,
                           row_number() OVER (
                               PARTITION BY knowledge_base_id
                               ORDER BY document_updated_at DESC, document_id
                           ) AS knowledge_base_document_rank
                    FROM eligible
                    WHERE chunk_rank = 1
                ), selected_documents AS (
                    SELECT document_id, knowledge_base_document_rank
                    FROM document_heads
                    ORDER BY knowledge_base_document_rank, knowledge_base_name, document_updated_at DESC, document_id
                    LIMIT ?
                )
                SELECT eligible.knowledge_base_id,
                       eligible.knowledge_base_name,
                       eligible.document_id,
                       eligible.document_version_id,
                       eligible.document_title,
                       eligible.chunk_text
                FROM eligible
                JOIN selected_documents selected ON selected.document_id = eligible.document_id
                WHERE eligible.chunk_rank <= 2
                ORDER BY selected.knowledge_base_document_rank,
                         eligible.knowledge_base_name,
                         eligible.document_updated_at DESC,
                         eligible.document_id,
                         eligible.chunk_rank
                LIMIT ?
                """;
        var parameters = new ArrayList<>(scoped.parameters());
        parameters.add(maximumDocuments);
        parameters.add(maximumExcerpts);
        return dsl.fetch(sql, parameters.toArray()).map(record -> new SourceExcerpt(
                record.get("knowledge_base_id", UUID.class),
                record.get("knowledge_base_name", String.class),
                record.get("document_id", UUID.class),
                record.get("document_version_id", UUID.class),
                record.get("document_title", String.class),
                compact(record.get("chunk_text", String.class), 1_100)
        )).stream().filter(value -> value.text() != null && !value.text().isBlank()).toList();
    }

    private String compact(String value, int maximum) {
        if (value == null) return "";
        var normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "…";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ContentSnapshot(String revision, Set<UUID> documentVersionIds) {
    }
}
