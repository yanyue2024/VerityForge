package com.yanyue.rag.application.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.evaluation.EvaluationComparisonView;
import com.yanyue.rag.contract.evaluation.EvaluationJudgeMode;
import com.yanyue.rag.contract.evaluation.EvaluationNotificationConfigRequest;
import com.yanyue.rag.contract.evaluation.SaveEvaluationScheduleRequest;
import com.yanyue.rag.contract.evaluation.StartEvaluationComparisonRequest;
import com.yanyue.rag.domain.evaluation.EvaluationCase;
import com.yanyue.rag.domain.evaluation.EvaluationDataset;
import com.yanyue.rag.domain.evaluation.EvaluationSchedule;
import com.yanyue.rag.domain.port.EvaluationRepository;
import com.yanyue.rag.domain.port.CredentialCipher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationAutomationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T09:00:00Z");

    @Test
    void createsAValidatedScheduleWithAFullComparisonRequest() {
        var fixture = fixture();
        var request = new SaveEvaluationScheduleRequest(
                "Release regression", 60, true, KnowledgeScope.all(), List.of(), null,
                EvaluationJudgeMode.ANSWER_AND_CITATIONS);
        var stored = fixture.schedule("Release regression", 60, true, configuration(request));
        when(fixture.repository.createSchedule(
                eq(fixture.organizationId), eq(fixture.userId), eq(fixture.datasetId),
                eq("Release regression"), eq(60), eq(true), anyMap(), eq(false), isNull(), isNull(),
                eq(NOW.plusSeconds(3600))))
                .thenReturn(stored);

        var result = fixture.service.create(
                fixture.organizationId, fixture.userId, fixture.datasetId, request);

        assertEquals("Release regression", result.name());
        assertEquals(EvaluationJudgeMode.ANSWER_AND_CITATIONS, result.judgeMode());
        assertTrue(result.scope().knowledgeBaseIds().isEmpty());
    }

    @Test
    void encryptsWebhookSecretsAndNeverReturnsTheCiphertext() {
        var fixture = fixture();
        var request = new SaveEvaluationScheduleRequest(
                "Signed webhook", 60, true, KnowledgeScope.all(), List.of(), null,
                EvaluationJudgeMode.NONE,
                new EvaluationNotificationConfigRequest(
                        true, "https://events.example.com/rag", "a-secret-with-16-characters"));
        var stored = fixture.schedule(
                "Signed webhook", 60, true, configuration(request), true,
                "https://events.example.com/rag", "encrypted-secret");
        when(fixture.credentialCipher.encrypt("a-secret-with-16-characters")).thenReturn("encrypted-secret");
        when(fixture.repository.createSchedule(
                eq(fixture.organizationId), eq(fixture.userId), eq(fixture.datasetId),
                eq("Signed webhook"), eq(60), eq(true), anyMap(), eq(true),
                eq("https://events.example.com/rag"), eq("encrypted-secret"),
                eq(NOW.plusSeconds(3600))))
                .thenReturn(stored);

        var result = fixture.service.create(
                fixture.organizationId, fixture.userId, fixture.datasetId, request);

        assertTrue(result.notification().enabled());
        assertTrue(result.notification().hasSigningSecret());
        assertEquals("https://events.example.com/rag", result.notification().webhookUrl());
    }

    @Test
    void atomicallyClaimedSchedulesLaunchAComparisonAndPersistItsIdentity() {
        var fixture = fixture();
        var request = new SaveEvaluationScheduleRequest(
                "Nightly", 1440, true, KnowledgeScope.all(), List.of(), null,
                EvaluationJudgeMode.ANSWER);
        var schedule = fixture.schedule("Nightly", 1440, true, configuration(request));
        var comparisonId = UUID.randomUUID();
        var comparison = new EvaluationComparisonView(
                comparisonId, fixture.datasetId, null, null, EvaluationJudgeMode.ANSWER, NOW);
        when(fixture.repository.claimDueSchedules(NOW, 10)).thenReturn(List.of(schedule));
        when(fixture.evaluationService.startComparison(
                eq(fixture.organizationId), eq(fixture.userId), eq(fixture.datasetId), any()))
                .thenReturn(comparison);

        assertEquals(1, fixture.service.dispatchDueSchedules());

        verify(fixture.repository).markScheduleTriggered(schedule.id(), comparisonId, NOW);
    }

    @Test
    void recordsScheduleLaunchFailuresWithoutStoppingThePollBatch() {
        var fixture = fixture();
        var request = new SaveEvaluationScheduleRequest(
                "Broken", 60, true, KnowledgeScope.all(), List.of(), null, EvaluationJudgeMode.NONE);
        var schedule = fixture.schedule("Broken", 60, true, configuration(request));
        when(fixture.repository.claimDueSchedules(NOW, 10)).thenReturn(List.of(schedule));
        when(fixture.evaluationService.startComparison(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("model unavailable"));

        assertEquals(1, fixture.service.dispatchDueSchedules());

        verify(fixture.repository).markScheduleFailed(schedule.id(), "model unavailable", NOW);
    }

    private Fixture fixture() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasetId = UUID.randomUUID();
        var repository = mock(EvaluationRepository.class);
        var evaluationService = mock(EvaluationService.class);
        var credentialCipher = mock(CredentialCipher.class);
        var mapper = new ObjectMapper();
        var dataset = new EvaluationDataset(datasetId, organizationId, "Regression", "", NOW);
        var evaluationCase = new EvaluationCase(
                UUID.randomUUID(), datasetId, "Question", "Answer", List.of(), Map.of());
        when(repository.findDataset(organizationId, datasetId)).thenReturn(Optional.of(dataset));
        when(repository.findCases(organizationId, datasetId)).thenReturn(List.of(evaluationCase));
        var service = new EvaluationAutomationService(
                repository, evaluationService, mapper, credentialCipher, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(
                organizationId, userId, datasetId, repository, evaluationService, credentialCipher, service);
    }

    private Map<String, Object> configuration(SaveEvaluationScheduleRequest request) {
        var values = new LinkedHashMap<>(new ObjectMapper().convertValue(
                request.comparisonRequest(), new TypeReference<Map<String, Object>>() { }));
        values.values().removeIf(java.util.Objects::isNull);
        return Map.copyOf(values);
    }

    private record Fixture(
            UUID organizationId,
            UUID userId,
            UUID datasetId,
            EvaluationRepository repository,
            EvaluationService evaluationService,
            CredentialCipher credentialCipher,
            EvaluationAutomationService service
    ) {
        EvaluationSchedule schedule(
                String name,
                int cadenceMinutes,
                boolean enabled,
                Map<String, Object> request
        ) {
            return schedule(name, cadenceMinutes, enabled, request, false, null, null);
        }

        EvaluationSchedule schedule(
                String name,
                int cadenceMinutes,
                boolean enabled,
                Map<String, Object> request,
                boolean webhookEnabled,
                String webhookUrl,
                String webhookSecretCiphertext
        ) {
            return new EvaluationSchedule(
                    UUID.randomUUID(), organizationId, datasetId, userId, name, cadenceMinutes, enabled,
                    request, webhookEnabled, webhookUrl, webhookSecretCiphertext, null,
                    NOW.plusSeconds(cadenceMinutes * 60L), null, null, null, NOW, NOW);
        }
    }
}
