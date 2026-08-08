package com.yanyue.rag.application.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.domain.evaluation.EvaluationNotificationDelivery;
import com.yanyue.rag.domain.evaluation.EvaluationRun;
import com.yanyue.rag.domain.evaluation.EvaluationRunStatus;
import com.yanyue.rag.domain.port.CredentialCipher;
import com.yanyue.rag.domain.port.EvaluationRepository;
import com.yanyue.rag.domain.port.WebhookDeliveryPort;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EvaluationNotificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T11:00:00Z");

    @Test
    void signsAndCompletesATerminalComparisonDelivery() {
        var fixture = fixture();
        var delivery = delivery(1);
        when(fixture.repository.claimReadyNotifications(NOW, NOW.minusSeconds(120), 10))
                .thenReturn(java.util.List.of(delivery));
        when(fixture.cipher.decrypt("encrypted-secret")).thenReturn("signed-webhook-secret");
        when(fixture.webhook.deliver(any(), any(), any())).thenReturn(
                new WebhookDeliveryPort.DeliveryResult(204, "accepted", true, false));

        assertEquals(1, fixture.service.dispatchReady());

        @SuppressWarnings("unchecked")
        var headers = ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        var body = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.webhook).deliver(eq(URI.create("https://events.example.com/rag")), body.capture(),
                headers.capture());
        assertTrue(headers.getValue().get("X-RAG-Signature").matches("v1=[0-9a-f]{64}"));
        assertEquals(delivery.id().toString(), headers.getValue().get("X-RAG-Idempotency-Key"));
        var payload = new String(body.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(payload.contains("rag.evaluation.notification/v1"));
        assertTrue(payload.contains(delivery.comparisonId().toString()));
        assertTrue(payload.contains("recallAt10"));
        verify(fixture.repository).completeNotification(delivery.id(), 1, 204, "accepted", NOW);
    }

    @Test
    void retryableResponseUsesPersistedBackoffWithoutFailingTheComparison() {
        var fixture = fixture();
        var delivery = delivery(1);
        when(fixture.repository.claimReadyNotifications(NOW, NOW.minusSeconds(120), 10))
                .thenReturn(java.util.List.of(delivery));
        when(fixture.cipher.decrypt("encrypted-secret")).thenReturn("signed-webhook-secret");
        when(fixture.webhook.deliver(any(), any(), any())).thenReturn(
                new WebhookDeliveryPort.DeliveryResult(503, "later", false, true));

        fixture.service.dispatchReady();

        verify(fixture.repository).failNotification(
                delivery.id(), 1, true, NOW.plusSeconds(30), 503, "later",
                "Webhook returned HTTP 503", NOW);
    }

    @Test
    void nonRetryableResponseMovesDirectlyToFailed() {
        var fixture = fixture();
        var delivery = delivery(2);
        when(fixture.repository.claimReadyNotifications(NOW, NOW.minusSeconds(120), 10))
                .thenReturn(java.util.List.of(delivery));
        when(fixture.cipher.decrypt("encrypted-secret")).thenReturn("signed-webhook-secret");
        when(fixture.webhook.deliver(any(), any(), any())).thenReturn(
                new WebhookDeliveryPort.DeliveryResult(400, "bad request", false, false));

        fixture.service.dispatchReady();

        verify(fixture.repository).failNotification(
                delivery.id(), 2, false, NOW, 400, "bad request",
                "Webhook returned HTTP 400", NOW);
    }

    private Fixture fixture() {
        var repository = mock(EvaluationRepository.class);
        var webhook = mock(WebhookDeliveryPort.class);
        var cipher = mock(CredentialCipher.class);
        var service = new EvaluationNotificationService(
                repository, webhook, cipher, new ObjectMapper().findAndRegisterModules(),
                RagTelemetry.noop(), Clock.fixed(NOW, ZoneOffset.UTC), 120);
        return new Fixture(repository, webhook, cipher, service);
    }

    private EvaluationNotificationDelivery delivery(int attempt) {
        var datasetId = UUID.randomUUID();
        return new EvaluationNotificationDelivery(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), datasetId,
                "Nightly", "Release checks", "https://events.example.com/rag", "encrypted-secret",
                "DELIVERING", attempt, 5, null, null, null, NOW, null, NOW.minusSeconds(60), NOW,
                run(datasetId, "FAST", 0.86), run(datasetId, "DEEP", 0.92));
    }

    private EvaluationRun run(UUID datasetId, String mode, double recall) {
        return new EvaluationRun(
                UUID.randomUUID(), datasetId, EvaluationRunStatus.COMPLETED,
                Map.of("requestedMode", mode, "recallAt10", recall),
                NOW.minusSeconds(30), NOW.minusSeconds(1), NOW.minusSeconds(60));
    }

    private record Fixture(
            EvaluationRepository repository,
            WebhookDeliveryPort webhook,
            CredentialCipher cipher,
            EvaluationNotificationService service
    ) {
    }
}
