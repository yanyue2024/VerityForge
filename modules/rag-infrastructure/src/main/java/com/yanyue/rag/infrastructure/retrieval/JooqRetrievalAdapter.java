package com.yanyue.rag.infrastructure.retrieval;

import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.domain.model.EmbeddingModelReference;
import com.yanyue.rag.domain.port.EmbeddingModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqRetrievalAdapter implements RetrievalPort {
    private static final String FROM_EFFECTIVE_CHUNKS = """
            FROM chunk c
            JOIN document_version dv ON dv.id = c.document_version_id
            JOIN document d ON d.id = dv.document_id
            JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
            """;
    private static final String SOURCE_LOCATION_FIELDS = """
            , COALESCE(
                (SELECT db.page_number
                 FROM chunk_source_segment segment
                 JOIN document_block db ON db.id = segment.document_block_id
                 WHERE segment.chunk_id = c.id
                 ORDER BY segment.segment_order LIMIT 1),
                (SELECT db.page_number
                 FROM document_block db
                 WHERE db.id = ANY(c.source_block_ids) AND strpos(db.block_text, c.chunk_text) > 0
                 ORDER BY db.order_index LIMIT 1),
                (SELECT min(db.page_number) FROM document_block db WHERE db.id = ANY(c.source_block_ids))
              ) AS page_number
            , COALESCE(
                (SELECT segment.document_source_start
                 FROM chunk_source_segment segment
                 WHERE segment.chunk_id = c.id AND segment.document_source_start IS NOT NULL
                 ORDER BY segment.segment_order LIMIT 1),
                (SELECT db.source_start + strpos(db.block_text, c.chunk_text) - 1
                 FROM document_block db
                 WHERE db.id = ANY(c.source_block_ids) AND db.source_start IS NOT NULL
                   AND strpos(db.block_text, c.chunk_text) > 0
                 ORDER BY db.order_index LIMIT 1),
                (SELECT min(db.source_start) FROM document_block db WHERE db.id = ANY(c.source_block_ids))
              ) AS source_start
            , COALESCE(
                (SELECT segment.document_source_end
                 FROM chunk_source_segment segment
                 WHERE segment.chunk_id = c.id AND segment.document_source_end IS NOT NULL
                 ORDER BY segment.segment_order DESC LIMIT 1),
                (SELECT db.source_start + strpos(db.block_text, c.chunk_text) - 1 + char_length(c.chunk_text)
                 FROM document_block db
                 WHERE db.id = ANY(c.source_block_ids) AND db.source_start IS NOT NULL
                   AND strpos(db.block_text, c.chunk_text) > 0
                 ORDER BY db.order_index LIMIT 1),
                (SELECT max(db.source_end) FROM document_block db WHERE db.id = ANY(c.source_block_ids))
              ) AS source_end
            """;

    private final DSLContext dsl;
    private final EmbeddingModelPort embeddings;
    private final RagTelemetry telemetry;
    private final RetrievalScopeSqlBuilder scopeBuilder = new RetrievalScopeSqlBuilder();

    public JooqRetrievalAdapter(DSLContext dsl, EmbeddingModelPort embeddings, RagTelemetry telemetry) {
        this.dsl = dsl;
        this.embeddings = embeddings;
        this.telemetry = telemetry;
    }

    @Override
    public List<RetrievalHit> keywordSearch(String query, RetrievalScope scope, int topK) {
        return telemetry.observe("rag.retrieval", java.util.Map.of("strategy", "keyword"),
                () -> keywordSearchInternal(query, scope, topK));
    }

    private List<RetrievalHit> keywordSearchInternal(String query, RetrievalScope scope, int topK) {
        var scoped = scopeBuilder.build(scope);
        var sql = """
                WITH search_input AS (
                    SELECT websearch_to_tsquery('simple', ?) AS parsed_query, ?::text AS raw_query
                )
                SELECT c.id AS chunk_id, c.parent_chunk_id, d.id AS document_id, dv.id AS document_version_id,
                       d.title AS document_title, c.chunk_text
                """ + SOURCE_LOCATION_FIELDS + """
                       ,
                       (ts_rank_cd(c.search_vector, si.parsed_query) +
                        similarity(lower(c.embedding_text), lower(si.raw_query)) * 0.2) AS score
                """ + FROM_EFFECTIVE_CHUNKS + """
                CROSS JOIN search_input si
                WHERE
                """ + scoped.predicate() + """
                  AND (c.search_vector @@ si.parsed_query OR similarity(lower(c.embedding_text), lower(si.raw_query)) > 0.03)
                ORDER BY score DESC, c.id
                LIMIT ?
                """;
        var parameters = new ArrayList<Object>();
        parameters.add(query);
        parameters.add(query);
        parameters.addAll(scoped.parameters());
        parameters.add(topK);
        return dsl.fetch(sql, parameters.toArray()).map(record -> map(record, "keyword"));
    }

    @Override
    public List<RetrievalHit> semanticSearch(String query, RetrievalScope scope, int topK, int overFetch) {
        return telemetry.observe("rag.retrieval", java.util.Map.of("strategy", "semantic"),
                () -> semanticSearchInternal(query, scope, topK, overFetch, true, null));
    }

    @Override
    public List<RetrievalHit> semanticSearchStrict(String query, RetrievalScope scope, int topK, int overFetch) {
        return telemetry.observe("rag.retrieval", java.util.Map.of("strategy", "semantic-strict"),
                () -> semanticSearchInternal(query, scope, topK, overFetch, false, null));
    }

    @Override
    public List<RetrievalHit> semanticSearchStrict(
            String query,
            RetrievalScope scope,
            int topK,
            int overFetch,
            java.time.Duration timeout
    ) {
        return telemetry.observe("rag.retrieval", java.util.Map.of("strategy", "semantic-strict"),
                () -> semanticSearchInternal(query, scope, topK, overFetch, false, timeout));
    }

    private List<RetrievalHit> semanticSearchInternal(
            String query,
            RetrievalScope scope,
            int topK,
            int overFetch,
            boolean allowKeywordFallback,
            java.time.Duration timeout
    ) {
        var scoped = scopeBuilder.build(scope);
        var generations = activeGenerations(scope);
        if (generations.isEmpty()) {
            if (allowKeywordFallback) return keywordSearch(query, scope, topK);
            throw new IllegalStateException("当前检索范围没有可用的语义索引");
        }
        var queryVectors = new java.util.HashMap<EmbeddingModelReference, String>();
        var merged = new LinkedHashMap<UUID, RetrievalHit>();
        for (var generation : generations) {
            var queryVector = queryVectors.computeIfAbsent(generation.model(), model -> {
                var vectors = timeout == null
                        ? embeddings.embed(model, List.of(query))
                        : embeddings.embed(model, List.of(query), timeout);
                if (vectors.size() != 1) {
                    throw new IllegalStateException("Embedding model returned an invalid query batch");
                }
                return PgVectorFormatter.format(vectors.getFirst());
            });
            for (var hit : searchGeneration(generation, queryVector, scoped, topK, overFetch)) {
                merged.merge(hit.chunkId(), hit,
                        (current, candidate) -> candidate.score() > current.score() ? candidate : current);
            }
        }
        var results = merged.values().stream()
                .sorted(java.util.Comparator.comparingDouble(RetrievalHit::score).reversed())
                .limit(topK)
                .toList();
        return results.isEmpty() && allowKeywordFallback ? keywordSearch(query, scope, topK) : results;
    }

    private List<RetrievalHit> searchGeneration(
            ActiveGeneration generation,
            String queryVector,
            ScopedSql scoped,
            int topK,
            int overFetch
    ) {
        var dimension = generation.model().dimension();
        if (dimension <= 0 || dimension > 16_384) {
            throw new IllegalStateException("Index Generation has an invalid embedding dimension");
        }
        var vectorType = "vector(" + dimension + ")";
        var sql = ("""
                SELECT c.id AS chunk_id, c.parent_chunk_id, d.id AS document_id, dv.id AS document_version_id,
                       d.title AS document_title, c.chunk_text
                """ + SOURCE_LOCATION_FIELDS + """
                       ,
                       1 - (ce.embedding::%s <=> ?::%s) AS score
                """ + FROM_EFFECTIVE_CHUNKS + """
                JOIN chunk_embedding ce
                  ON ce.chunk_id = c.id AND ce.index_generation_id = ? AND ce.dimension = ?
                WHERE
                """ + scoped.predicate() + """
                ORDER BY ce.embedding::%s <=> ?::%s
                LIMIT ?
                """).formatted(vectorType, vectorType, vectorType, vectorType);
        var parameters = new ArrayList<Object>();
        parameters.add(queryVector);
        parameters.add(generation.id());
        parameters.add(dimension);
        parameters.addAll(scoped.parameters());
        parameters.add(queryVector);
        parameters.add(Math.max(topK, topK * overFetch));
        return dsl.fetch(sql, parameters.toArray()).map(record -> map(record, "semantic"));
    }

    private List<ActiveGeneration> activeGenerations(RetrievalScope scope) {
        var sql = new StringBuilder("""
                SELECT ig.id, ig.embedding_profile_id, ig.embedding_model_id,
                       ig.embedding_model_version, ig.embedding_dimension
                FROM index_generation ig
                JOIN knowledge_base kb ON kb.id = ig.knowledge_base_id
                WHERE kb.organization_id = ? AND ig.status = 'ACTIVE'
                """);
        var parameters = new ArrayList<Object>();
        parameters.add(scope.organizationId());
        if (!scope.knowledgeBaseIds().isEmpty()) {
            sql.append(" AND ig.knowledge_base_id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(scope.knowledgeBaseIds().size(), "?")))
                    .append(')');
            parameters.addAll(scope.knowledgeBaseIds());
        }
        return dsl.fetch(sql.toString(), parameters.toArray()).map(record -> new ActiveGeneration(
                record.get("id", UUID.class),
                new EmbeddingModelReference(
                        record.get("embedding_profile_id", UUID.class),
                        record.get("embedding_model_id", String.class),
                        record.get("embedding_model_version", String.class),
                        record.get("embedding_dimension", Integer.class)
                )
        ));
    }

    @Override
    public List<RetrievalHit> expandContext(List<RetrievalHit> hits, int finalGroups) {
        return telemetry.observe("rag.retrieval", java.util.Map.of("strategy", "context_expand"),
                () -> expandContextInternal(hits, finalGroups));
    }

    private List<RetrievalHit> expandContextInternal(List<RetrievalHit> hits, int finalGroups) {
        var primary = new ArrayList<RetrievalHit>();
        var seen = new HashSet<UUID>();
        int groups = 0;
        for (var hit : hits) {
            var groupId = hit.parentChunkId() == null ? hit.chunkId() : hit.parentChunkId();
            if (!seen.add(groupId)) continue;
            if (hit.parentChunkId() == null) {
                primary.add(hit);
            } else {
                var parent = dsl.fetchOptional("""
                        SELECT c.id, c.chunk_text
                        """ + SOURCE_LOCATION_FIELDS + """
                        FROM chunk c
                        WHERE c.id = ? AND c.document_version_id = ?
                          AND c.chunk_type = 'PARENT' AND c.enabled = true
                        """, hit.parentChunkId(), hit.documentVersionId());
                var parentHit = parent.map(record -> new RetrievalHit(
                        record.get("id", UUID.class), null, hit.documentId(), hit.documentVersionId(), hit.documentTitle(),
                        record.get("chunk_text", String.class), hit.score(), append(hit.sources(), "parent-expand"),
                        record.get("page_number", Integer.class), record.get("source_start", Integer.class),
                        record.get("source_end", Integer.class)
                )).orElse(hit);
                primary.add(parentHit);
            }
            groups++;
            if (groups >= finalGroups) break;
        }
        return List.copyOf(primary);
    }

    private RetrievalHit map(Record record, String source) {
        var score = record.get("score", Double.class);
        return new RetrievalHit(
                record.get("chunk_id", UUID.class),
                record.get("parent_chunk_id", UUID.class),
                record.get("document_id", UUID.class),
                record.get("document_version_id", UUID.class),
                record.get("document_title", String.class),
                record.get("chunk_text", String.class),
                score == null ? 0 : score,
                List.of(source),
                record.get("page_number", Integer.class),
                record.get("source_start", Integer.class),
                record.get("source_end", Integer.class)
        );
    }

    private List<String> append(List<String> current, String value) {
        var copy = new ArrayList<>(current);
        copy.add(value);
        return List.copyOf(copy);
    }

    private record ActiveGeneration(UUID id, EmbeddingModelReference model) {
    }
}
