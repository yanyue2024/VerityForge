package com.yanyue.rag.domain.port;

import java.util.List;

public interface LanguageModelPort {
    String rewriteQuery(String query, List<String> recentMessages);
    List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates, int topK);
    String generate(String query, List<RetrievalHit> context);
}
