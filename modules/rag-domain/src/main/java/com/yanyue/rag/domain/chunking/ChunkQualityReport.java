package com.yanyue.rag.domain.chunking;

import java.util.List;
import java.util.Map;

public record ChunkQualityReport(
        ChunkQualityStatus status,
        int score,
        List<ChunkQualityIssue> issues,
        Map<String, Object> metrics
) {
    public ChunkQualityReport {
        status = status == null ? ChunkQualityStatus.WARNING : status;
        score = Math.max(0, Math.min(100, score));
        issues = issues == null ? List.of() : List.copyOf(issues);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
