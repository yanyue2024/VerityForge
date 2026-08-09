package com.yanyue.rag.application.chat.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.domain.agent.budget.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.deep.DeepRagProfiles;
import com.yanyue.rag.domain.agent.budget.BudgetDimension;
import com.yanyue.rag.domain.port.DeepRunArtifactPort;
import com.yanyue.rag.domain.port.StructuredReasoningModelPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeepModelInvokerTest {
    private StructuredReasoningModelPort model;
    private DeepRunArtifactPort artifacts;
    private DeepModelInvoker invoker;

    @BeforeEach
    void setUp() {
        model = mock(StructuredReasoningModelPort.class);
        artifacts = mock(DeepRunArtifactPort.class);
        when(artifacts.claimModelAttempt(any())).thenReturn(true);
        invoker = new DeepModelInvoker(model,
                Clock.fixed(Instant.parse("2026-07-23T08:00:00Z"), ZoneOffset.UTC), artifacts);
    }

    @Test
    void validContractUsesOnePhysicalAttempt() {
        when(model.completeJson(any(), anyString(), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn("valid");

        var result = invoke(raw -> raw);

        assertEquals("valid", result);
        verify(model, times(1)).completeJson(any(), anyString(), anyString(), anyString(), any(), anyInt(), anyInt());
        verify(artifacts).completeLogicalModelCall(any(), eq(true), eq(false), eq(null), anyString());
    }

    @Test
    void invalidContractUsesExactlyOneRepairAttempt() {
        when(model.completeJson(any(), eq("deep-test"), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn("invalid");
        when(model.completeJson(any(), eq("deep-test-json-repair"), anyString(), anyString(), any(), anyInt(),
                anyInt()))
                .thenReturn("valid");

        var result = invoke(raw -> {
            if (!"valid".equals(raw)) throw new IllegalArgumentException("invalid-json");
            return raw;
        });

        assertEquals("valid", result);
        verify(model, times(2)).completeJson(any(), anyString(), anyString(), anyString(), any(), anyInt(), anyInt());
        verify(artifacts).completeLogicalModelCall(any(), eq(true), eq(true), eq(null), anyString());
    }

    @Test
    void transientTransportFailureRetriesTheOriginalRequestWithoutJsonRepair() {
        when(model.completeJson(any(), eq("deep-test"), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("deep-test returned HTTP 503"))
                .thenReturn("valid");

        var result = invoke(raw -> raw);

        assertEquals("valid", result);
        verify(model, times(2)).completeJson(any(), eq("deep-test"), anyString(), anyString(), any(),
                anyInt(), anyInt());
        verify(artifacts).completeLogicalModelCall(any(), eq(true), eq(false), eq(null), anyString());
        verify(artifacts).reserveModelAttempt(any(), any(), any(), anyString(), eq("deep-test"),
                eq("deep-test"), eq(2), any(), anyInt());
    }

    @Test
    void nonRetryableModelFailureDoesNotConsumeTheSecondAttempt() {
        when(model.completeJson(any(), eq("deep-test"), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("invalid credentials"));

        assertThrows(IllegalStateException.class, () -> invoke(raw -> raw));

        verify(model, times(1)).completeJson(any(), eq("deep-test"), anyString(), anyString(), any(),
                anyInt(), anyInt());
        verify(artifacts).completeLogicalModelCall(any(), eq(false), eq(false),
                eq("IllegalStateException"), eq(null));
    }

    @Test
    void auditFailureAfterValidParseDoesNotTriggerRepair() {
        when(model.completeJson(any(), anyString(), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn("valid");
        doThrow(new IllegalStateException("audit-failed")).when(artifacts)
                .completeLogicalModelCall(any(), eq(true), eq(false), eq(null), anyString());

        assertThrows(IllegalStateException.class, () -> invoke(raw -> raw));

        verify(model, times(1)).completeJson(any(), anyString(), anyString(), anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void optionalStageDeadlineCapsTheModelRequestTimeout() {
        when(model.completeJson(any(), anyString(), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn("valid");
        var startedAt = Instant.parse("2026-07-23T08:00:00Z");
        var ledger = new AgentBudgetLedger(DeepRagProfiles.finalProfile(), startedAt);

        invoker.invokeJson(UUID.randomUUID(), UUID.randomUUID(), "request-analysis",
                "deep-test", "system", "user", 100,
                BudgetDimension.REQUEST_ANALYSIS_CALL, ledger, startedAt.plusSeconds(15), raw -> raw);

        var timeout = ArgumentCaptor.forClass(Duration.class);
        verify(model).completeJson(any(), anyString(), anyString(), anyString(), timeout.capture(), anyInt(), anyInt());
        assertEquals(Duration.ofSeconds(13), timeout.getValue());
    }

    @Test
    void goalScopedEvidenceJudgeStoresGoalSeparatelyFromPhase() {
        when(model.completeJson(any(), anyString(), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn("valid");
        var goalId = UUID.randomUUID();
        var ledger = new AgentBudgetLedger(DeepRagProfiles.finalProfile(),
                Instant.parse("2026-07-23T08:00:00Z"));

        invoker.invokeJson(UUID.randomUUID(), UUID.randomUUID(), "evidence-judge:" + goalId,
                "deep-test", "system", "user", 100,
                BudgetDimension.EVIDENCE_JUDGE_CALL, ledger, raw -> raw);

        verify(artifacts).reserveModelAttempt(any(), any(), eq(goalId), eq("EVIDENCE_JUDGE"),
                eq("deep-test"), eq("deep-test"), eq(1), any(), anyInt());
    }

    private <T> T invoke(java.util.function.Function<String, T> parser) {
        var ledger = new AgentBudgetLedger(DeepRagProfiles.finalProfile(),
                Instant.parse("2026-07-23T08:00:00Z"));
        return invoker.invokeJson(UUID.randomUUID(), UUID.randomUUID(), "request-analysis",
                "deep-test", "system", "user", 100,
                BudgetDimension.REQUEST_ANALYSIS_CALL, ledger, parser);
    }
}
