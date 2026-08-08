package com.yanyue.rag.application.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.contract.evaluation.EvaluationNotificationDeliveryView;
import com.yanyue.rag.domain.evaluation.EvaluationNotificationDelivery;
import com.yanyue.rag.domain.evaluation.EvaluationRun;
import com.yanyue.rag.domain.port.CredentialCipher;
import com.yanyue.rag.domain.port.EvaluationRepository;
import com.yanyue.rag.domain.port.WebhookDeliveryPort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EvaluationNotificationService {
    private static final String EVENT_TYPE = "evaluation.comparison.completed";
    private static final int MAX_CLAIMS = 10;

    private final EvaluationRepository repository;
    private final WebhookDeliveryPort deliveryPort;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;
    private final RagTelemetry telemetry;
    private final Clock clock;
    private final Duration staleAfter;

    public EvaluationNotificationService(
            EvaluationRepository repository,
            WebhookDeliveryPort deliveryPort,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            RagTelemetry telemetry,
            Clock clock,
            @Value("${rag.evaluation.notifications.stale-after-seconds:120}") long staleAfterSeconds
    ) {
        this.repository = repository;
        this.deliveryPort = deliveryPort;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
        this.clock = clock;
        this.staleAfter = Duration.ofSeconds(Math.max(10, staleAfterSeconds));
    }

    public int dispatchReady() {
        var now = clock.instant();
        var claimed = repository.claimReadyNotifications(now, now.minus(staleAfter), MAX_CLAIMS);
        for (var delivery : claimed) deliver(delivery);
        return claimed.size();
    }

    public List<EvaluationNotificationDeliveryView> deliveries(
            UUID organizationId,
            UUID scheduleId,
            int limit
    ) {
        repository.findSchedule(organizationId, scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation schedule not found"));
        return repository.findNotifications(organizationId, scheduleId, Math.max(1, Math.min(limit, 50)))
                .stream().map(this::view).toList();
    }

    public EvaluationNotificationDeliveryView retry(UUID organizationId, UUID deliveryId) {
        if (!repository.retryNotification(organizationId, deliveryId, clock.instant())) {
            throw new IllegalArgumentException("Failed evaluation notification not found");
        }
        return repository.findNotification(organizationId, deliveryId).map(this::view)
                .orElseThrow(() -> new IllegalStateException("Retried evaluation notification is unavailable"));
    }

    private void deliver(EvaluationNotificationDelivery delivery) {
        var tags = Map.of("attempt", Integer.toString(delivery.attempt()));
        try {
            var body = payload(delivery);
            var timestamp = Long.toString(clock.instant().getEpochSecond());
            var secret = credentialCipher.decrypt(delivery.signingSecretCiphertext());
            var headers = Map.of(
                    "Content-Type", "application/json",
                    "X-RAG-Event", EVENT_TYPE,
                    "X-RAG-Delivery", delivery.id().toString(),
                    "X-RAG-Idempotency-Key", delivery.id().toString(),
                    "X-RAG-Timestamp", timestamp,
                    "X-RAG-Signature", "v1=" + signature(secret, timestamp, body)
            );
            var result = telemetry.observe("rag.evaluation.notification", tags,
                    () -> deliveryPort.deliver(URI.create(delivery.webhookUrl()), body, headers));
            if (result.successful()) {
                repository.completeNotification(delivery.id(), delivery.attempt(), result.statusCode(),
                        response(result.responseBody()), clock.instant());
                return;
            }
            fail(delivery, result.retryable(), result.statusCode(), result.responseBody(),
                    "Webhook returned HTTP " + result.statusCode());
        } catch (RuntimeException failure) {
            telemetry.increment("rag.evaluation.notification.error", Map.of(
                    "exception", failure.getClass().getSimpleName()));
            fail(delivery, true, null, null, message(failure));
        }
    }

    private void fail(
            EvaluationNotificationDelivery delivery,
            boolean retryable,
            Integer responseStatus,
            String responseBody,
            String error
    ) {
        boolean retry = retryable && delivery.attempt() < delivery.maxAttempts();
        var now = clock.instant();
        repository.failNotification(
                delivery.id(), delivery.attempt(), retry,
                retry ? now.plus(backoff(delivery.attempt())) : now,
                responseStatus, response(responseBody), error, now);
    }

    private byte[] payload(EvaluationNotificationDelivery value) {
        var root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", "rag.evaluation.notification/v1");
        root.put("event", EVENT_TYPE);
        root.put("deliveryId", value.id().toString());
        root.put("schedule", Map.of(
                "id", value.scheduleId().toString(),
                "name", value.scheduleName()
        ));
        root.put("dataset", Map.of(
                "id", value.datasetId().toString(),
                "name", value.datasetName()
        ));
        root.put("comparison", Map.of(
                "id", value.comparisonId().toString(),
                "fast", run(value.fastRun()),
                "deep", run(value.deepRun())
        ));
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to serialize evaluation notification", failure);
        }
    }

    private Map<String, Object> run(EvaluationRun value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", value.id().toString());
        result.put("status", value.status().name());
        result.put("metrics", value.aggregateMetrics());
        result.put("startedAt", value.startedAt() == null ? null : value.startedAt().toString());
        result.put("completedAt", value.completedAt() == null ? null : value.completedAt().toString());
        return result;
    }

    private EvaluationNotificationDeliveryView view(EvaluationNotificationDelivery value) {
        return new EvaluationNotificationDeliveryView(
                value.id(), value.scheduleId(), value.comparisonId(), value.status(), value.attempt(),
                value.maxAttempts(), value.responseStatus(), value.responseBody(), value.errorMessage(),
                value.nextAttemptAt(), value.deliveredAt(), value.createdAt(), value.updatedAt());
    }

    private String signature(String secret, String timestamp, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }

    private Duration backoff(int attempt) {
        return switch (attempt) {
            case 1 -> Duration.ofSeconds(30);
            case 2 -> Duration.ofMinutes(2);
            case 3 -> Duration.ofMinutes(10);
            default -> Duration.ofMinutes(30);
        };
    }

    private String response(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private String message(Throwable failure) {
        var value = failure.getMessage();
        if (value == null || value.isBlank()) return failure.getClass().getSimpleName();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }
}
