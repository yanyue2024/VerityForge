package com.yanyue.rag.contract.evaluation;

import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.chat.RunMode;
import java.util.List;
import java.util.UUID;

public record StartEvaluationRunRequest(
        RunMode mode,
        KnowledgeScope scope,
        List<MetadataFilter> filters,
        UUID modelProfileId,
        EvaluationJudgeMode judgeMode
) {
    public StartEvaluationRunRequest {
        mode = mode == null ? RunMode.FAST : mode;
        scope = scope == null ? KnowledgeScope.all() : scope;
        filters = filters == null ? List.of() : List.copyOf(filters);
        judgeMode = judgeMode == null ? EvaluationJudgeMode.NONE : judgeMode;
    }

    public StartEvaluationRunRequest(
            RunMode mode,
            KnowledgeScope scope,
            List<MetadataFilter> filters,
            UUID modelProfileId
    ) {
        this(mode, scope, filters, modelProfileId, EvaluationJudgeMode.NONE);
    }

    public static StartEvaluationRunRequest defaults() {
        return new StartEvaluationRunRequest(
                RunMode.FAST, KnowledgeScope.all(), List.of(), null, EvaluationJudgeMode.NONE);
    }
}
