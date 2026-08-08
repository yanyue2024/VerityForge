package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.UUID;

public interface RetrievalTracePort {
    void save(UUID runId, String query, long latencyMillis, List<CandidateTrace> candidates);

    default void save(
            UUID runId,
            UUID subQuestionId,
            String query,
            String strategy,
            long latencyMillis,
            List<CandidateTrace> candidates
    ) {
        save(runId, query, latencyMillis, candidates);
    }

    record CandidateTrace(
            RetrievalHit hit,
            Integer keywordRank,
            Integer semanticRank,
            Double rrfScore,
            Double rerankScore,
            boolean acceptedContext
    ) {
    }
}
