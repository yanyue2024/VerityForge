package com.yanyue.rag.contract.evaluation;

import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.MetadataFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SaveEvaluationScheduleRequest(
        @NotBlank @Size(max = 120) String name,
        @Min(15) @Max(10080) int cadenceMinutes,
        boolean enabled,
        @Valid KnowledgeScope scope,
        List<@Valid MetadataFilter> filters,
        UUID modelProfileId,
        EvaluationJudgeMode judgeMode,
        @Valid EvaluationNotificationConfigRequest notification
) {
    public SaveEvaluationScheduleRequest {
        scope = scope == null ? KnowledgeScope.all() : scope;
        filters = filters == null ? List.of() : List.copyOf(filters);
        judgeMode = judgeMode == null ? EvaluationJudgeMode.NONE : judgeMode;
        notification = notification == null ? EvaluationNotificationConfigRequest.disabled() : notification;
    }

    public SaveEvaluationScheduleRequest(
            String name,
            int cadenceMinutes,
            boolean enabled,
            KnowledgeScope scope,
            List<MetadataFilter> filters,
            UUID modelProfileId,
            EvaluationJudgeMode judgeMode
    ) {
        this(name, cadenceMinutes, enabled, scope, filters, modelProfileId, judgeMode,
                EvaluationNotificationConfigRequest.disabled());
    }

    public StartEvaluationComparisonRequest comparisonRequest() {
        return new StartEvaluationComparisonRequest(scope, filters, modelProfileId, judgeMode);
    }
}
