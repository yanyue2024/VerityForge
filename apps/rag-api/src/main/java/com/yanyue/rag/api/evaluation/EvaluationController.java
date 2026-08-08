package com.yanyue.rag.api.evaluation;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.evaluation.EvaluationService;
import com.yanyue.rag.application.evaluation.EvaluationAutomationService;
import com.yanyue.rag.application.evaluation.EvaluationNotificationService;
import com.yanyue.rag.contract.evaluation.CreateEvaluationCaseRequest;
import com.yanyue.rag.contract.evaluation.CreateEvaluationDatasetRequest;
import com.yanyue.rag.contract.evaluation.EvaluationCaseView;
import com.yanyue.rag.contract.evaluation.EvaluationComparisonDetailView;
import com.yanyue.rag.contract.evaluation.EvaluationComparisonView;
import com.yanyue.rag.contract.evaluation.EvaluationDatasetDetailView;
import com.yanyue.rag.contract.evaluation.EvaluationDatasetBundle;
import com.yanyue.rag.contract.evaluation.EvaluationDatasetView;
import com.yanyue.rag.contract.evaluation.EvaluationRunDetailView;
import com.yanyue.rag.contract.evaluation.EvaluationRunView;
import com.yanyue.rag.contract.evaluation.EvaluationRunSummaryView;
import com.yanyue.rag.contract.evaluation.StartEvaluationRunRequest;
import com.yanyue.rag.contract.evaluation.StartEvaluationComparisonRequest;
import com.yanyue.rag.contract.evaluation.EvaluationScheduleView;
import com.yanyue.rag.contract.evaluation.EvaluationTrendPointView;
import com.yanyue.rag.contract.evaluation.SaveEvaluationScheduleRequest;
import com.yanyue.rag.contract.evaluation.EvaluationNotificationDeliveryView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {
    private final EvaluationService service;
    private final EvaluationAutomationService automationService;
    private final EvaluationNotificationService notificationService;

    public EvaluationController(
            EvaluationService service,
            EvaluationAutomationService automationService,
            EvaluationNotificationService notificationService
    ) {
        this.service = service;
        this.automationService = automationService;
        this.notificationService = notificationService;
    }

    @GetMapping("/datasets")
    public List<EvaluationDatasetView> datasets(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.listDatasets(user.organizationId());
    }

    @GetMapping("/runs")
    public List<EvaluationRunSummaryView> runs(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return service.listRuns(user.organizationId(), limit);
    }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationDatasetView createDataset(@AuthenticationPrincipal AuthenticatedUser user,
                                               @Valid @RequestBody CreateEvaluationDatasetRequest request) {
        return service.createDataset(user.organizationId(), request);
    }

    @PostMapping("/datasets/import")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationDatasetDetailView importDataset(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody EvaluationDatasetBundle bundle
    ) {
        return service.importDataset(user.organizationId(), bundle);
    }

    @GetMapping("/datasets/{datasetId}/export")
    public ResponseEntity<EvaluationDatasetBundle> exportDataset(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID datasetId
    ) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"evaluation-" + datasetId + ".json\"")
                .body(service.exportDataset(user.organizationId(), datasetId));
    }

    @GetMapping("/datasets/{datasetId}")
    public EvaluationDatasetDetailView dataset(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable UUID datasetId) {
        return service.dataset(user.organizationId(), datasetId);
    }

    @PostMapping("/datasets/{datasetId}/cases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationCaseView addCase(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable UUID datasetId,
                                      @Valid @RequestBody CreateEvaluationCaseRequest request) {
        return service.addCase(user.organizationId(), datasetId, request);
    }

    @DeleteMapping("/datasets/{datasetId}/cases/{caseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public void deleteCase(@AuthenticationPrincipal AuthenticatedUser user,
                           @PathVariable UUID datasetId,
                           @PathVariable UUID caseId) {
        service.deleteCase(user.organizationId(), datasetId, caseId);
    }

    @PostMapping("/datasets/{datasetId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationRunView startRun(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable UUID datasetId,
                                      @RequestBody(required = false) StartEvaluationRunRequest request) {
        return service.startRagRun(user.organizationId(), user.userId(), datasetId,
                request == null ? StartEvaluationRunRequest.defaults() : request);
    }

    @PostMapping("/datasets/{datasetId}/routing-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationRunView startRoutingRun(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID datasetId,
            @RequestBody(required = false) StartEvaluationRunRequest request
    ) {
        return service.startRoutingRun(user.organizationId(), datasetId,
                request == null ? StartEvaluationRunRequest.defaults() : request);
    }

    @PostMapping("/datasets/{datasetId}/retrieval-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationRunView startRetrievalRun(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID datasetId,
            @RequestBody(required = false) StartEvaluationRunRequest request
    ) {
        return service.startRetrievalRun(user.organizationId(), datasetId,
                request == null ? StartEvaluationRunRequest.defaults() : request);
    }

    @PostMapping("/datasets/{datasetId}/agentic-retrieval-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationRunView startAgenticRetrievalRun(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID datasetId,
            @RequestBody(required = false) StartEvaluationRunRequest request
    ) {
        return service.startAgenticRetrievalRun(user.organizationId(), user.userId(), datasetId,
                request == null ? StartEvaluationRunRequest.defaults() : request);
    }

    @PostMapping("/runs/{runId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationRunView resumeRun(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID runId,
            @RequestBody(required = false) StartEvaluationRunRequest request
    ) {
        return service.resumeRun(user.organizationId(), user.userId(), runId, request);
    }

    @DeleteMapping("/runs/{runId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public void cancelRun(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID runId
    ) {
        service.cancelRun(user.organizationId(), runId);
    }

    @PostMapping("/datasets/{datasetId}/comparisons")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationComparisonView startComparison(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID datasetId,
            @RequestBody(required = false) StartEvaluationComparisonRequest request
    ) {
        return service.startComparison(
                user.organizationId(), user.userId(), datasetId,
                request == null
                        ? new StartEvaluationComparisonRequest(null, null, null, null)
                        : request);
    }

    @GetMapping("/comparisons/{comparisonId}")
    public EvaluationComparisonDetailView comparison(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID comparisonId
    ) {
        return service.comparison(user.organizationId(), comparisonId);
    }

    @GetMapping("/datasets/{datasetId}/trends")
    public List<EvaluationTrendPointView> trends(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID datasetId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return automationService.trends(user.organizationId(), datasetId, limit);
    }

    @GetMapping("/datasets/{datasetId}/schedules")
    public List<EvaluationScheduleView> schedules(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID datasetId
    ) {
        return automationService.schedules(user.organizationId(), datasetId);
    }

    @PostMapping("/datasets/{datasetId}/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationScheduleView createSchedule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID datasetId,
            @Valid @RequestBody SaveEvaluationScheduleRequest request
    ) {
        return automationService.create(user.organizationId(), user.userId(), datasetId, request);
    }

    @PutMapping("/schedules/{scheduleId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationScheduleView updateSchedule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody SaveEvaluationScheduleRequest request
    ) {
        return automationService.update(user.organizationId(), scheduleId, request);
    }

    @DeleteMapping("/schedules/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public void deleteSchedule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID scheduleId
    ) {
        automationService.delete(user.organizationId(), scheduleId);
    }

    @PostMapping("/schedules/{scheduleId}/run-now")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationComparisonView runScheduleNow(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID scheduleId
    ) {
        return automationService.runNow(user.organizationId(), scheduleId);
    }

    @GetMapping("/schedules/{scheduleId}/notifications")
    public List<EvaluationNotificationDeliveryView> notifications(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID scheduleId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return notificationService.deliveries(user.organizationId(), scheduleId, limit);
    }

    @PostMapping("/notifications/{deliveryId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public EvaluationNotificationDeliveryView retryNotification(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID deliveryId
    ) {
        return notificationService.retry(user.organizationId(), deliveryId);
    }

    @GetMapping("/runs/{runId}")
    public EvaluationRunDetailView run(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID runId) {
        return service.run(user.organizationId(), runId);
    }
}
