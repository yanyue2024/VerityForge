package com.yanyue.rag.api.evaluation;

import com.yanyue.rag.application.evaluation.EvaluationAutomationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvaluationScheduleDispatcher {
    private final EvaluationAutomationService automationService;

    public EvaluationScheduleDispatcher(EvaluationAutomationService automationService) {
        this.automationService = automationService;
    }

    @Scheduled(
            initialDelayString = "${rag.evaluation.schedule-initial-delay-ms:30000}",
            fixedDelayString = "${rag.evaluation.schedule-poll-ms:30000}"
    )
    public void dispatch() {
        automationService.dispatchDueSchedules();
    }
}
