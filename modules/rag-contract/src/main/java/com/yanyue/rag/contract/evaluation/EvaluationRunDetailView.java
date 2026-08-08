package com.yanyue.rag.contract.evaluation;

import java.util.List;
import java.util.Map;

public record EvaluationRunDetailView(
        EvaluationRunView run,
        EvaluationDatasetView dataset,
        Map<String, Object> requestSnapshot,
        List<EvaluationResultView> results
) {
    public EvaluationRunDetailView {
        requestSnapshot = requestSnapshot == null ? Map.of() : Map.copyOf(requestSnapshot);
        results = results == null ? List.of() : List.copyOf(results);
    }
}
