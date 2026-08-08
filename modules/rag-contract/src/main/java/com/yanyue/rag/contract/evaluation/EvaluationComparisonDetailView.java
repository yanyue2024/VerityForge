package com.yanyue.rag.contract.evaluation;

public record EvaluationComparisonDetailView(
        EvaluationComparisonView comparison,
        EvaluationRunDetailView fast,
        EvaluationRunDetailView deep
) {
}
