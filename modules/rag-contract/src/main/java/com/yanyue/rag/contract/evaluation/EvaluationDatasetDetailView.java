package com.yanyue.rag.contract.evaluation;

import java.util.List;

public record EvaluationDatasetDetailView(
        EvaluationDatasetView dataset,
        List<EvaluationCaseView> cases,
        List<EvaluationRunView> runs
) {
}
