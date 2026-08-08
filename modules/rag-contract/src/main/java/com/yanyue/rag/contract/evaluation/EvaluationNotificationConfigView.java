package com.yanyue.rag.contract.evaluation;

public record EvaluationNotificationConfigView(
        boolean enabled,
        String webhookUrl,
        boolean hasSigningSecret
) {
}
