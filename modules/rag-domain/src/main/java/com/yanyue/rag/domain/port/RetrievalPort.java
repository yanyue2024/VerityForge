package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.util.List;
import java.time.Duration;

public interface RetrievalPort {
    List<RetrievalHit> keywordSearch(String query, RetrievalScope scope, int topK);
    List<RetrievalHit> semanticSearch(String query, RetrievalScope scope, int topK, int overFetch);

    /** Strict semantic retrieval must not fall back to keyword retrieval. */
    default List<RetrievalHit> semanticSearchStrict(String query, RetrievalScope scope, int topK, int overFetch) {
        return semanticSearch(query, scope, topK, overFetch);
    }

    default List<RetrievalHit> semanticSearchStrict(
            String query,
            RetrievalScope scope,
            int topK,
            int overFetch,
            Duration timeout
    ) {
        return semanticSearchStrict(query, scope, topK, overFetch);
    }

    List<RetrievalHit> expandContext(List<RetrievalHit> hits, int finalGroups);
}
