package com.yanyue.rag.domain.agent;

import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.util.UUID;

public record RetrievalTask(
        UUID id,
        UUID subQuestionId,
        String query,
        SearchMode searchMode,
        RetrievalScope scope,
        int topK
) {
}
