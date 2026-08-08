package com.yanyue.rag.contract.parser;

import java.util.List;
import java.util.Map;

public record ParseQualityReport(
        ParseQualityStatus status,
        int score,
        List<ParseQualityIssue> issues,
        Map<String, Object> metrics
) {
    public ParseQualityReport {
        status = status == null ? ParseQualityStatus.WARNING : status;
        score = Math.max(0, Math.min(100, score));
        issues = issues == null ? List.of() : List.copyOf(issues);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public static ParseQualityReport legacyPass() {
        return new ParseQualityReport(ParseQualityStatus.PASS, 100, List.of(),
                Map.of("assessment", "legacy-parser-contract"));
    }
}
