package com.yanyue.rag.contract.evaluation;

import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.MetadataFilter;
import java.util.List;
import java.util.UUID;

public record StartEvaluationComparisonRequest(
        KnowledgeScope scope,
        List<MetadataFilter> filters,
        UUID modelProfileId,
        EvaluationJudgeMode judgeMode
) {
    public StartEvaluationComparisonRequest {
        scope = scope == null ? KnowledgeScope.all() : scope;
        filters = filters == null ? List.of() : List.copyOf(filters);
        judgeMode = judgeMode == null ? EvaluationJudgeMode.NONE : judgeMode;
    }
}
