package com.yanyue.rag.contract.evaluation;

import jakarta.validation.constraints.Size;

public record EvaluationNotificationConfigRequest(
        boolean enabled,
        @Size(max = 2048) String webhookUrl,
        @Size(max = 512) String signingSecret
) {
    public EvaluationNotificationConfigRequest {
        webhookUrl = webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl.strip();
    }

    public static EvaluationNotificationConfigRequest disabled() {
        return new EvaluationNotificationConfigRequest(false, null, null);
    }
}
