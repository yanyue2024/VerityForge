package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.UUID;
import java.time.Duration;

public interface RerankModelPort {
    List<RerankScore> rerank(UUID profileId, String query, List<String> documents, int topK);

    default List<RerankScore> rerank(
            UUID profileId,
            String query,
            List<String> documents,
            int topK,
            Duration timeout
    ) {
        return rerank(profileId, query, documents, topK);
    }

    record RerankScore(int index, double score) {
    }
}
