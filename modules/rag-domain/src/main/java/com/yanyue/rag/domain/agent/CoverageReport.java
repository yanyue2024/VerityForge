package com.yanyue.rag.domain.agent;

import java.util.List;
import java.util.UUID;

public record CoverageReport(UUID runId, List<SubQuestionCoverage> items) {
    public CoverageReport {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean sufficient() {
        return !items.isEmpty() && items.stream().allMatch(item -> item.covered() && !item.hasConflict());
    }
}
