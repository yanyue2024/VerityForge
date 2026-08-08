package com.yanyue.rag.api.evaluation;

import com.yanyue.rag.application.evaluation.EvaluationNotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvaluationNotificationDispatcher {
    private final EvaluationNotificationService service;

    public EvaluationNotificationDispatcher(EvaluationNotificationService service) {
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${rag.evaluation.notifications.initial-delay-ms:15000}",
            fixedDelayString = "${rag.evaluation.notifications.poll-ms:10000}"
    )
    public void dispatch() {
        service.dispatchReady();
    }
}
