package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yanyue.rag.domain.port.RetrievalTracePort;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqRetrievalTraceAdapter implements RetrievalTracePort {
    private final DSLContext dsl;
    public JooqRetrievalTraceAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void save(UUID runId, String query, long latencyMillis, List<CandidateTrace> candidates) {
        save(runId, null, query, "HYBRID", latencyMillis, candidates);
    }

    @Override
    public void save(
            UUID runId,
            UUID subQuestionId,
            String query,
            String strategy,
            long latencyMillis,
            List<CandidateTrace> candidates
    ) {
        dsl.transaction(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("""
                    INSERT INTO retrieval_query
                        (run_id, sub_question_id, query_text, strategy, filters, result_count, latency_ms)
                    VALUES (?, ?, ?, ?, '[]'::jsonb, ?, ?)
                    """, runId, subQuestionId, query, strategy, candidates.size(),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, latencyMillis)));
            for (var candidate : candidates) {
                var hit = candidate.hit();
                tx.execute("""
                        INSERT INTO retrieval_candidate
                            (run_id, chunk_id, keyword_rank, semantic_rank, rrf_score, rerank_score,
                             accepted_context, retrieval_sources)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (run_id, chunk_id) DO UPDATE
                        SET keyword_rank = LEAST(retrieval_candidate.keyword_rank, EXCLUDED.keyword_rank),
                            semantic_rank = LEAST(retrieval_candidate.semantic_rank, EXCLUDED.semantic_rank),
                            rrf_score = GREATEST(retrieval_candidate.rrf_score, EXCLUDED.rrf_score),
                            rerank_score = GREATEST(retrieval_candidate.rerank_score, EXCLUDED.rerank_score),
                            accepted_context = retrieval_candidate.accepted_context OR EXCLUDED.accepted_context,
                            retrieval_sources = EXCLUDED.retrieval_sources
                        """, runId, hit.chunkId(), candidate.keywordRank(), candidate.semanticRank(),
                        candidate.rrfScore(), candidate.rerankScore(), candidate.acceptedContext(),
                        hit.sources().toArray(String[]::new));
            }
        });
    }
}
