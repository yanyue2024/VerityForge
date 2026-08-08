package com.yanyue.rag.application.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.evaluation.EvaluationComparisonView;
import com.yanyue.rag.contract.evaluation.EvaluationJudgeMode;
import com.yanyue.rag.contract.evaluation.EvaluationNotificationConfigView;
import com.yanyue.rag.contract.evaluation.EvaluationNotificationDeliveryView;
import com.yanyue.rag.contract.evaluation.EvaluationRunView;
import com.yanyue.rag.contract.evaluation.EvaluationScheduleView;
import com.yanyue.rag.contract.evaluation.EvaluationTrendPointView;
import com.yanyue.rag.contract.evaluation.SaveEvaluationScheduleRequest;
import com.yanyue.rag.contract.evaluation.StartEvaluationComparisonRequest;
import com.yanyue.rag.domain.evaluation.EvaluationRun;
import com.yanyue.rag.domain.evaluation.EvaluationSchedule;
import com.yanyue.rag.domain.port.EvaluationRepository;
import com.yanyue.rag.domain.port.CredentialCipher;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EvaluationAutomationService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int MAX_CLAIMS_PER_POLL = 10;

    private final EvaluationRepository repository;
    private final EvaluationService evaluationService;
    private final ObjectMapper objectMapper;
    private final CredentialCipher credentialCipher;
    private final Clock clock;

    public EvaluationAutomationService(
            EvaluationRepository repository,
            EvaluationService evaluationService,
            ObjectMapper objectMapper,
            CredentialCipher credentialCipher,
            Clock clock
    ) {
        this.repository = repository;
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
        this.credentialCipher = credentialCipher;
        this.clock = clock;
    }

    public List<EvaluationScheduleView> schedules(UUID organizationId, UUID datasetId) {
        requireDataset(organizationId, datasetId);
        return repository.findSchedules(organizationId, datasetId).stream().map(this::view).toList();
    }

    public EvaluationScheduleView create(
            UUID organizationId,
            UUID userId,
            UUID datasetId,
            SaveEvaluationScheduleRequest request
    ) {
        requireRunnableDataset(organizationId, datasetId);
        validate(request);
        var now = clock.instant();
        var notification = notification(request, null);
        return view(repository.createSchedule(
                organizationId, userId, datasetId, request.name().strip(), request.cadenceMinutes(),
                request.enabled(), configuration(request.comparisonRequest()), notification.enabled(),
                notification.webhookUrl(), notification.secretCiphertext(),
                now.plus(Duration.ofMinutes(request.cadenceMinutes()))));
    }

    public EvaluationScheduleView update(
            UUID organizationId,
            UUID scheduleId,
            SaveEvaluationScheduleRequest request
    ) {
        var existing = requireSchedule(organizationId, scheduleId);
        requireRunnableDataset(organizationId, existing.datasetId());
        validate(request);
        var nextRun = clock.instant().plus(Duration.ofMinutes(request.cadenceMinutes()));
        var notification = notification(request, existing);
        return view(repository.updateSchedule(
                organizationId, scheduleId, request.name().strip(), request.cadenceMinutes(),
                request.enabled(), configuration(request.comparisonRequest()), notification.enabled(),
                notification.webhookUrl(), notification.secretCiphertext(), nextRun));
    }

    public void delete(UUID organizationId, UUID scheduleId) {
        if (!repository.deleteSchedule(organizationId, scheduleId)) {
            throw new IllegalArgumentException("Evaluation schedule not found");
        }
    }

    public EvaluationComparisonView runNow(UUID organizationId, UUID scheduleId) {
        var schedule = requireSchedule(organizationId, scheduleId);
        requireRunnableDataset(organizationId, schedule.datasetId());
        return launch(schedule);
    }

    public List<EvaluationTrendPointView> trends(UUID organizationId, UUID datasetId, int limit) {
        requireDataset(organizationId, datasetId);
        return repository.findComparisonTrends(organizationId, datasetId, Math.max(1, Math.min(limit, 50)))
                .stream()
                .map(point -> new EvaluationTrendPointView(
                        point.comparison().id(), point.comparison().datasetId(),
                        EvaluationJudgeMode.valueOf(point.comparison().judgeMode()),
                        runView(point.fast()), runView(point.deep()), point.comparison().createdAt()))
                .toList();
    }

    public int dispatchDueSchedules() {
        var claimed = repository.claimDueSchedules(clock.instant(), MAX_CLAIMS_PER_POLL);
        for (var schedule : claimed) {
            try {
                launch(schedule);
            } catch (RuntimeException failure) {
                repository.markScheduleFailed(schedule.id(), message(failure), clock.instant());
            }
        }
        return claimed.size();
    }

    private EvaluationComparisonView launch(EvaluationSchedule schedule) {
        var comparison = evaluationService.startComparison(
                schedule.organizationId(), schedule.createdBy(), schedule.datasetId(),
                comparisonRequest(schedule));
        repository.markScheduleTriggered(schedule.id(), comparison.id(), clock.instant());
        return comparison;
    }

    private StartEvaluationComparisonRequest comparisonRequest(EvaluationSchedule schedule) {
        try {
            return objectMapper.convertValue(schedule.request(), StartEvaluationComparisonRequest.class);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Evaluation schedule contains an invalid request", failure);
        }
    }

    private Map<String, Object> configuration(StartEvaluationComparisonRequest request) {
        var values = new java.util.LinkedHashMap<>(objectMapper.convertValue(request, MAP_TYPE));
        values.values().removeIf(java.util.Objects::isNull);
        return Map.copyOf(values);
    }

    private EvaluationScheduleView view(EvaluationSchedule schedule) {
        var request = comparisonRequest(schedule);
        return new EvaluationScheduleView(
                schedule.id(), schedule.datasetId(), schedule.name(), schedule.cadenceMinutes(),
                schedule.enabled(), request.scope(), request.filters(), request.modelProfileId(),
                request.judgeMode(), new EvaluationNotificationConfigView(
                        schedule.webhookEnabled(), schedule.webhookUrl(),
                        schedule.webhookSecretCiphertext() != null),
                notificationView(schedule), schedule.nextRunAt(), schedule.lastRunAt(),
                schedule.lastComparisonId(), schedule.lastError(), schedule.createdAt(), schedule.updatedAt());
    }

    private EvaluationNotificationDeliveryView notificationView(EvaluationSchedule schedule) {
        var value = schedule.lastNotification();
        if (value == null) return null;
        return new EvaluationNotificationDeliveryView(
                value.id(), schedule.id(), value.comparisonId(), value.status(), value.attempt(),
                value.maxAttempts(), value.responseStatus(), null, value.errorMessage(), null, null,
                null, value.updatedAt());
    }

    private EvaluationRunView runView(EvaluationRun run) {
        return new EvaluationRunView(
                run.id(), run.datasetId(), run.status().name(), run.aggregateMetrics(),
                run.startedAt(), run.completedAt(), run.createdAt());
    }

    private EvaluationSchedule requireSchedule(UUID organizationId, UUID scheduleId) {
        return repository.findSchedule(organizationId, scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation schedule not found"));
    }

    private void requireDataset(UUID organizationId, UUID datasetId) {
        repository.findDataset(organizationId, datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation dataset not found"));
    }

    private void requireRunnableDataset(UUID organizationId, UUID datasetId) {
        requireDataset(organizationId, datasetId);
        if (repository.findCases(organizationId, datasetId).isEmpty()) {
            throw new IllegalArgumentException("Evaluation dataset has no cases");
        }
    }

    private void validate(SaveEvaluationScheduleRequest request) {
        if (request.name() == null || request.name().isBlank() || request.name().strip().length() > 120) {
            throw new IllegalArgumentException("Evaluation schedule name is invalid");
        }
        if (request.cadenceMinutes() < 15 || request.cadenceMinutes() > 10_080) {
            throw new IllegalArgumentException("Evaluation schedule cadence must be between 15 and 10080 minutes");
        }
    }

    private NotificationSettings notification(SaveEvaluationScheduleRequest request, EvaluationSchedule existing) {
        var supplied = request.notification();
        var endpoint = supplied.webhookUrl() != null
                ? supplied.webhookUrl()
                : existing == null ? null : existing.webhookUrl();
        var encryptedSecret = existing == null ? null : existing.webhookSecretCiphertext();
        if (supplied.signingSecret() != null) {
            if (supplied.signingSecret().length() < 16) {
                throw new IllegalArgumentException("Webhook signing secret must contain at least 16 characters");
            }
            encryptedSecret = credentialCipher.encrypt(supplied.signingSecret());
        }
        if (endpoint != null) validateWebhookUrl(endpoint);
        if ((endpoint == null) != (encryptedSecret == null)) {
            throw new IllegalArgumentException("Webhook endpoint and signing secret must be configured together");
        }
        if (supplied.enabled() && (endpoint == null || encryptedSecret == null)) {
            throw new IllegalArgumentException("Enabled webhook notifications require an endpoint and signing secret");
        }
        return new NotificationSettings(supplied.enabled(), endpoint, encryptedSecret);
    }

    private void validateWebhookUrl(String value) {
        try {
            var uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Webhook endpoint must be an HTTP(S) URL without credentials or fragment");
            }
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("Webhook endpoint")) throw failure;
            throw new IllegalArgumentException("Webhook endpoint is not a valid URL", failure);
        }
    }

    private String message(RuntimeException failure) {
        var value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private record NotificationSettings(boolean enabled, String webhookUrl, String secretCiphertext) {
    }
}
