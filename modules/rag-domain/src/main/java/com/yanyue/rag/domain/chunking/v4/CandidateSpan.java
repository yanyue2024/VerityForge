package com.yanyue.rag.domain.chunking.v4;

import java.util.UUID;
import java.util.List;

public record CandidateSpan(
        String spanId,
        UUID parentChunkId,
        int localStart,
        int localEnd,
        String text,
        List<String> titlePath,
        SourceAnchor sourceAnchor,
        int estimatedTokens,
        int childAnchorDistance,
        int localRelevanceScore
) {
    public CandidateSpan {
        titlePath = titlePath == null ? List.of() : List.copyOf(titlePath);
        if (spanId == null || spanId.isBlank()) throw new IllegalArgumentException("spanId 不能为空");
        if (localStart < 0 || localEnd <= localStart) throw new IllegalArgumentException("Span 范围无效");
        if (text == null || text.length() != localEnd - localStart) {
            throw new IllegalArgumentException("Span 文本必须与 UTF-16 本地范围逐字一致");
        }
        if (sourceAnchor == null || sourceAnchor.parentLocalStart() != localStart
                || sourceAnchor.parentLocalEnd() != localEnd) {
            throw new IllegalArgumentException("Span 与 SourceAnchor 范围必须一致");
        }
        if (estimatedTokens < 1 || estimatedTokens > CandidateSpanBuilder.MAX_SPAN_TOKENS) {
            throw new IllegalArgumentException("Span Token 估算超出 v4 边界");
        }
        if (childAnchorDistance < 0 || localRelevanceScore < 0) {
            throw new IllegalArgumentException("Span 排序指标不能为负数");
        }
    }
}
