package com.yanyue.rag.contract.evaluation;

import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.MetadataFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EvaluationScheduleView(
        UUID id,
        UUID datasetId,
        String name,
        int cadenceMinutes,
        boolean enabled,
        KnowledgeScope scope,
        List<MetadataFilter> filters,
        UUID modelProfileId,
        EvaluationJudgeMode judgeMode,
        EvaluationNotificationConfigView notification,
        EvaluationNotificationDeliveryView lastNotification,
        Instant nextRunAt,
        Instant lastRunAt,
        UUID lastComparisonId,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
