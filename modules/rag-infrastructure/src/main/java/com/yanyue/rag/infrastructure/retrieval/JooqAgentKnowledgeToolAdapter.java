package com.yanyue.rag.infrastructure.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.port.AgentKnowledgeToolPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class JooqAgentKnowledgeToolAdapter implements AgentKnowledgeToolPort {
    private static final int MAX_REGEX_LENGTH = 256;
    private static final int MAX_RESULTS = 100;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String SOURCE_LOCATION_FIELDS = """
            , COALESCE(
                (SELECT block.page_number
                 FROM chunk_source_segment segment
                 JOIN document_block block ON block.id = segment.document_block_id
                 WHERE segment.chunk_id = c.id
                 ORDER BY segment.segment_order LIMIT 1),
                (SELECT min(block.page_number) FROM document_block block
                 WHERE block.id = ANY(c.source_block_ids))
              ) AS page_number
            , COALESCE(
                (SELECT segment.document_source_start
                 FROM chunk_source_segment segment
                 WHERE segment.chunk_id = c.id AND segment.document_source_start IS NOT NULL
                 ORDER BY segment.segment_order LIMIT 1),
                (SELECT min(block.source_start) FROM document_block block
                 WHERE block.id = ANY(c.source_block_ids))
              ) AS source_start
            , COALESCE(
                (SELECT segment.document_source_end
                 FROM chunk_source_segment segment
                 WHERE segment.chunk_id = c.id AND segment.document_source_end IS NOT NULL
                 ORDER BY segment.segment_order DESC LIMIT 1),
                (SELECT max(block.source_end) FROM document_block block
                 WHERE block.id = ANY(c.source_block_ids))
              ) AS source_end
            """;

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final RetrievalScopeSqlBuilder scopes = new RetrievalScopeSqlBuilder();

    public JooqAgentKnowledgeToolAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<KnowledgeChunk> grepChunks(String expression, RetrievalScope scope, int limit) {
        if (expression == null || expression.isBlank() || expression.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException("grep expression must contain 1..256 characters");
        }
        try {
            Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException failure) {
            throw new IllegalArgumentException("grep expression is not a valid regular expression", failure);
        }
        var scoped = scopes.build(scope);
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("SET LOCAL statement_timeout = '5s'");
            return tx.fetch("""
                    SELECT d.knowledge_base_id, c.id AS chunk_id, c.parent_chunk_id,
                           d.id AS document_id, dv.id AS document_version_id, d.title,
                           c.chunk_text, dv.metadata,
                           CASE WHEN d.title ~* ? THEN 1.0 ELSE 0.8 END AS score
                    """ + SOURCE_LOCATION_FIELDS + """
                    FROM chunk c
                    JOIN document_version dv ON dv.id = c.document_version_id
                    JOIN document d ON d.id = dv.document_id
                    WHERE
                    """ + scoped.predicate() + """
                      AND (c.chunk_text ~* ? OR d.title ~* ?)
                    ORDER BY CASE WHEN d.title ~* ? THEN 0 ELSE 1 END, c.order_index, c.id
                    LIMIT ?
                    """, grepParameters(scoped.parameters(), expression, limit).toArray())
                    .map(record -> chunk(record, "grep"));
        });
    }

    private List<Object> grepParameters(List<Object> scopeParameters, String expression, int limit) {
        var values = new ArrayList<Object>();
        values.add(expression);
        values.addAll(scopeParameters);
        values.add(expression);
        values.add(expression);
        values.add(expression);
        values.add(Math.min(MAX_RESULTS, Math.max(1, limit)));
        return values;
    }

    @Override
    public List<KnowledgeChunk> listKnowledgeChunks(
            UUID knowledgeId, UUID chunkId, RetrievalScope scope, int offset, int limit) {
        if (knowledgeId == null && chunkId == null) {
            throw new IllegalArgumentException("knowledge_id or chunk_id is required");
        }
        var scoped = scopes.build(scope);
        var parameters = new ArrayList<Object>(scoped.parameters());
        parameters.add(knowledgeId);
        parameters.add(knowledgeId);
        parameters.add(chunkId);
        parameters.add(Math.min(50, Math.max(1, limit)));
        parameters.add(Math.max(0, offset));
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("SET LOCAL statement_timeout = '5s'");
            return tx.fetch("""
                    SELECT d.knowledge_base_id, c.id AS chunk_id, c.parent_chunk_id,
                           d.id AS document_id, dv.id AS document_version_id, d.title,
                           c.chunk_text, dv.metadata, 1.0 AS score
                    """ + SOURCE_LOCATION_FIELDS + """
                    FROM chunk c
                    JOIN document_version dv ON dv.id = c.document_version_id
                    JOIN document d ON d.id = dv.document_id
                    WHERE
                    """ + scoped.predicate() + """
                      AND (d.id = ? OR d.knowledge_base_id = ? OR c.id = ?)
                    ORDER BY c.order_index, c.id
                    LIMIT ? OFFSET ?
                    """, parameters.toArray()).map(record -> chunk(record, "deep-read"));
        });
    }

    @Override
    public List<DocumentInfo> getDocumentInfo(List<UUID> knowledgeIds, RetrievalScope scope) {
        if (knowledgeIds == null || knowledgeIds.isEmpty() || knowledgeIds.size() > 50) {
            throw new IllegalArgumentException("knowledge_ids must contain 1..50 identifiers");
        }
        var scoped = scopes.build(scope);
        var placeholders = String.join(",", java.util.Collections.nCopies(knowledgeIds.size(), "?"));
        var parameters = new ArrayList<Object>(scoped.parameters());
        parameters.addAll(knowledgeIds);
        return dsl.fetch("""
                SELECT DISTINCT d.knowledge_base_id, d.id AS document_id, dv.id AS document_version_id,
                       d.title, dv.source_name, dv.source_type, dv.version_label, dv.owner_name,
                       dv.business_domain, dv.tags, dv.metadata
                FROM chunk c
                JOIN document_version dv ON dv.id = c.document_version_id
                JOIN document d ON d.id = dv.document_id
                WHERE
                """ + scoped.predicate() + " AND (d.id IN (" + placeholders + ") OR d.knowledge_base_id IN ("
                + placeholders + ")) ORDER BY d.title", duplicateTail(parameters, knowledgeIds).toArray())
                .map(record -> new DocumentInfo(
                        record.get("knowledge_base_id", UUID.class), record.get("document_id", UUID.class),
                        record.get("document_version_id", UUID.class), record.get("title", String.class),
                        record.get("source_name", String.class), record.get("source_type", String.class),
                        record.get("version_label", String.class), record.get("owner_name", String.class),
                        record.get("business_domain", String.class),
                        tags(record.get("tags", String[].class)), json(record.get("metadata", JSONB.class))));
    }

    private List<Object> duplicateTail(List<Object> parameters, List<UUID> ids) {
        var values = new ArrayList<Object>(parameters);
        values.addAll(ids);
        return values;
    }

    private KnowledgeChunk chunk(org.jooq.Record record, String source) {
        return new KnowledgeChunk(record.get("knowledge_base_id", UUID.class), new RetrievalHit(
                record.get("chunk_id", UUID.class), record.get("parent_chunk_id", UUID.class),
                record.get("document_id", UUID.class), record.get("document_version_id", UUID.class),
                record.get("title", String.class), record.get("chunk_text", String.class),
                record.get("score", Double.class), List.of(source), record.get("page_number", Integer.class),
                record.get("source_start", Integer.class), record.get("source_end", Integer.class)),
                json(record.get("metadata", JSONB.class)));
    }

    private Map<String, Object> json(JSONB value) {
        if (value == null) return Map.of();
        try {
            return objectMapper.readValue(value.data(), MAP_TYPE);
        } catch (Exception failure) {
            throw new IllegalStateException("Document metadata is invalid", failure);
        }
    }

    private List<String> tags(String[] values) {
        return values == null ? List.of() : Arrays.asList(values);
    }
}
