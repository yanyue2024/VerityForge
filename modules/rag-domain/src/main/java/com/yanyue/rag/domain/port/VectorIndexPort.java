package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.util.List;
import java.util.UUID;

public interface VectorIndexPort {
    void upsert(UUID indexGenerationId, List<EmbeddingVector> vectors);
    List<RetrievalHit> semanticSearch(UUID indexGenerationId, List<Float> queryVector, RetrievalScope scope, int topK, int overFetch);
    void deleteGeneration(UUID indexGenerationId);
}
