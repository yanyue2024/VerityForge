package com.yanyue.rag.domain.chunking;

import java.util.UUID;

public record QueryProvenance(String queryId, UUID childChunkId, double retrievalScore) {
    public QueryProvenance {
        if (queryId == null || queryId.isBlank()) throw new IllegalArgumentException("queryId 不能为空");
        if (!Double.isFinite(retrievalScore)) throw new IllegalArgumentException("检索分数必须是有限数值");
    }
}
