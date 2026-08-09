package com.yanyue.rag.application.chat.deep;

import com.yanyue.rag.domain.agent.budget.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.budget.BudgetDimension;
import com.yanyue.rag.domain.port.StructuredReasoningModelPort;
import com.yanyue.rag.domain.port.DeepRunArtifactPort;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class DeepModelInvoker {
    private final StructuredReasoningModelPort model;
    private final Clock clock;
    private final DeepRunArtifactPort artifacts;

    public DeepModelInvoker(StructuredReasoningModelPort model, Clock clock, DeepRunArtifactPort artifacts) {
        this.model = model;
        this.clock = clock;
        this.artifacts = artifacts;
    }

    public <T> T invokeJson(
            UUID profileId,
            UUID runId,
            String actionKey,
            String operation,
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            BudgetDimension operationDimension,
            AgentBudgetLedger ledger,
            Function<String, T> parser
    ) {
        return invokeJson(profileId, runId, actionKey, operation, systemPrompt, userPrompt,
                maximumOutputTokens, operationDimension, ledger, ledger.deadline(), parser);
    }

    public <T> T invokeJson(
            UUID profileId,
            UUID runId,
            String actionKey,
            String operation,
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            BudgetDimension operationDimension,
            AgentBudgetLedger ledger,
            java.time.Instant operationDeadline,
            Function<String, T> parser
    ) {
        var logicalCallId = stableId(runId + ":" + actionKey);
        var goalId = goalId(actionKey);
        var phase = phase(actionKey);
        var first = reserve(ledger, actionKey + ":attempt-1", operationDimension,
                estimatedTokens(systemPrompt + userPrompt), maximumOutputTokens, true);
        artifacts.reserveModelAttempt(runId, logicalCallId, goalId, phase, operation, operation,
                1, first, systemPrompt.length() + userPrompt.length());
        ledger.markDispatched(first.reservationId(), clock.instant());
        if (!artifacts.claimModelAttempt(first.reservationId())) {
            ledger.fail(first.reservationId(), Map.of(), clock.instant());
            throw new IllegalStateException("模型调用预算动作无法 claim");
        }
        long started = System.nanoTime();
        String output;
        try {
            output = model.completeJson(profileId, operation, systemPrompt, userPrompt,
                    remaining(ledger, operationDeadline), maximumOutputTokens, 1);
        } catch (RuntimeException failure) {
            artifacts.completeModelAttempt(logicalCallId, first.reservationId(), 1, false, false, true,
                    estimatedTokens(systemPrompt + userPrompt), 0, elapsedMillis(started),
                    failure.getClass().getSimpleName(), null);
            ledger.fail(first.reservationId(), Map.of(), clock.instant());
            if (isRetryableTransportFailure(failure) && hasRepairCapacity(actionKey, ledger)) {
                return retryTransport(profileId, runId, logicalCallId, goalId, phase, actionKey, operation,
                        systemPrompt, userPrompt, maximumOutputTokens, ledger, operationDeadline, parser, failure);
            }
            artifacts.completeLogicalModelCall(logicalCallId, false, false,
                    failure.getClass().getSimpleName(), null);
            throw failure;
        }
        T parsed;
        try {
            parsed = parser.apply(output);
        } catch (RuntimeException invalid) {
            completeSuccessfulAttempt(logicalCallId, first, 1, false,
                    systemPrompt + userPrompt, output, started, ledger);
            return repair(profileId, runId, logicalCallId, goalId, phase, actionKey, operation, systemPrompt,
                    output, maximumOutputTokens, ledger, operationDeadline, parser, invalid);
        }
        completeSuccessfulAttempt(logicalCallId, first, 1, false,
                systemPrompt + userPrompt, output, started, ledger);
        artifacts.completeLogicalModelCall(logicalCallId, true, false, null, hash(output));
        return parsed;
    }

    private <T> T retryTransport(
            UUID profileId,
            UUID runId,
            UUID logicalCallId,
            UUID goalId,
            String phase,
            String actionKey,
            String operation,
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            AgentBudgetLedger ledger,
            java.time.Instant operationDeadline,
            Function<String, T> parser,
            RuntimeException firstFailure
    ) {
        try {
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(750L, 1_501L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            artifacts.completeLogicalModelCall(logicalCallId, false, false,
                    interrupted.getClass().getSimpleName(), null);
            var cancellation = new java.util.concurrent.CancellationException("模型传输重试等待被取消");
            cancellation.addSuppressed(firstFailure);
            throw cancellation;
        }

        com.yanyue.rag.domain.agent.budget.BudgetReservation second;
        try {
            second = reserve(ledger, actionKey + ":attempt-2", null,
                    estimatedTokens(systemPrompt + userPrompt), maximumOutputTokens, false);
            artifacts.reserveModelAttempt(runId, logicalCallId, goalId, phase, operation, operation,
                    2, second, systemPrompt.length() + userPrompt.length());
            ledger.markDispatched(second.reservationId(), clock.instant());
            if (!artifacts.claimModelAttempt(second.reservationId())) {
                ledger.fail(second.reservationId(), Map.of(), clock.instant());
                throw new IllegalStateException("模型传输重试预算动作无法 claim");
            }
        } catch (RuntimeException preparationFailure) {
            artifacts.completeLogicalModelCall(logicalCallId, false, false,
                    preparationFailure.getClass().getSimpleName(), null);
            preparationFailure.addSuppressed(firstFailure);
            throw preparationFailure;
        }

        long retryStarted = System.nanoTime();
        String output;
        try {
            output = model.completeJson(profileId, operation, systemPrompt, userPrompt,
                    remaining(ledger, operationDeadline), maximumOutputTokens, 1);
        } catch (RuntimeException retryFailure) {
            artifacts.completeModelAttempt(logicalCallId, second.reservationId(), 2, false, false, true,
                    estimatedTokens(systemPrompt + userPrompt), 0, elapsedMillis(retryStarted),
                    retryFailure.getClass().getSimpleName(), null);
            artifacts.completeLogicalModelCall(logicalCallId, false, false,
                    retryFailure.getClass().getSimpleName(), null);
            ledger.fail(second.reservationId(), Map.of(), clock.instant());
            retryFailure.addSuppressed(firstFailure);
            throw retryFailure;
        }

        T parsed;
        try {
            parsed = parser.apply(output);
        } catch (RuntimeException invalidRetry) {
            completeSuccessfulAttempt(logicalCallId, second, 2, false,
                    systemPrompt + userPrompt, output, retryStarted, ledger);
            artifacts.completeLogicalModelCall(logicalCallId, false, false,
                    invalidRetry.getClass().getSimpleName(), hash(output));
            invalidRetry.addSuppressed(firstFailure);
            throw invalidRetry;
        }
        completeSuccessfulAttempt(logicalCallId, second, 2, false,
                systemPrompt + userPrompt, output, retryStarted, ledger);
        artifacts.completeLogicalModelCall(logicalCallId, true, false, null, hash(output));
        return parsed;
    }

    private <T> T repair(
            UUID profileId,
            UUID runId,
            UUID logicalCallId,
            UUID goalId,
            String phase,
            String actionKey,
            String operation,
            String systemPrompt,
            String invalidOutput,
            int maximumOutputTokens,
            AgentBudgetLedger ledger,
            java.time.Instant operationDeadline,
            Function<String, T> parser,
            RuntimeException invalid
    ) {
        if (!hasRepairCapacity(actionKey, ledger)) {
            artifacts.completeLogicalModelCall(logicalCallId, false, true,
                    "REPAIR_CAPACITY_RESERVED", hash(invalidOutput));
            throw new IllegalStateException("结构化输出无效，额外尝试额度需为后续必需调用保留", invalid);
        }
        var repairPrompt = "修复以下输出，使其严格满足原系统提示的 JSON 契约。只输出 JSON，不解释。\n\n原输出：\n"
                + invalidOutput;
        com.yanyue.rag.domain.agent.budget.BudgetReservation second;
        try {
            second = reserve(ledger, actionKey + ":attempt-2", null,
                    estimatedTokens(systemPrompt + repairPrompt), maximumOutputTokens, false);
            artifacts.reserveModelAttempt(runId, logicalCallId, goalId, phase, operation, operation,
                    2, second, systemPrompt.length() + repairPrompt.length());
            ledger.markDispatched(second.reservationId(), clock.instant());
            if (!artifacts.claimModelAttempt(second.reservationId())) {
                ledger.fail(second.reservationId(), Map.of(), clock.instant());
                throw new IllegalStateException("模型 Repair 预算动作无法 claim");
            }
        } catch (RuntimeException repairPreparationFailure) {
            artifacts.completeLogicalModelCall(logicalCallId, false, true,
                    repairPreparationFailure.getClass().getSimpleName(), null);
            repairPreparationFailure.addSuppressed(invalid);
            throw repairPreparationFailure;
        }
        long repairStarted = System.nanoTime();
        String repaired;
        try {
            repaired = model.completeJson(profileId, operation + "-json-repair", systemPrompt, repairPrompt,
                    remaining(ledger, operationDeadline), maximumOutputTokens, 1);
        } catch (RuntimeException transportFailure) {
            artifacts.completeModelAttempt(logicalCallId, second.reservationId(), 2, false, true, true,
                    estimatedTokens(systemPrompt + repairPrompt), 0, elapsedMillis(repairStarted),
                    transportFailure.getClass().getSimpleName(), null);
            artifacts.completeLogicalModelCall(logicalCallId, false, true,
                    transportFailure.getClass().getSimpleName(), null);
            ledger.fail(second.reservationId(), Map.of(), clock.instant());
            transportFailure.addSuppressed(invalid);
            throw transportFailure;
        }
        T parsed;
        try {
            parsed = parser.apply(repaired);
        } catch (RuntimeException invalidRepair) {
            completeSuccessfulAttempt(logicalCallId, second, 2, true,
                    systemPrompt + repairPrompt, repaired, repairStarted, ledger);
            artifacts.completeLogicalModelCall(logicalCallId, false, true,
                    invalidRepair.getClass().getSimpleName(), hash(repaired));
            invalidRepair.addSuppressed(invalid);
            throw invalidRepair;
        }
        completeSuccessfulAttempt(logicalCallId, second, 2, true,
                systemPrompt + repairPrompt, repaired, repairStarted, ledger);
        artifacts.completeLogicalModelCall(logicalCallId, true, true, null, hash(repaired));
        return parsed;
    }

    private boolean hasRepairCapacity(String actionKey, AgentBudgetLedger ledger) {
        long remaining = ledger.snapshot().remaining()
                .getOrDefault(BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT, 0L);
        long requiredFutureAttempts;
        if ("request-analysis".equals(actionKey)) {
            requiredFutureAttempts = 8;
        } else if (actionKey.startsWith("deep-read:PRIMARY:")) {
            requiredFutureAttempts = 7;
        } else if (actionKey.startsWith("evidence-judge")) {
            requiredFutureAttempts = 4;
        } else if (actionKey.startsWith("deep-read:REPAIR:")) {
            requiredFutureAttempts = 3;
        } else {
            requiredFutureAttempts = 0;
        }
        return remaining > requiredFutureAttempts;
    }

    private boolean isRetryableTransportFailure(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.CancellationException
                    || current instanceof InterruptedException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return false;
            }
            var message = current.getMessage();
            if (message == null) continue;
            var normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.matches(".*http 5[0-9]{2}.*")
                    || normalized.contains("http 429")
                    || normalized.contains("request failed")
                    || normalized.contains("connection reset")
                    || normalized.contains("temporarily unavailable")) {
                return true;
            }
        }
        return false;
    }

    private void completeSuccessfulAttempt(
            UUID logicalCallId,
            com.yanyue.rag.domain.agent.budget.BudgetReservation reservation,
            int attemptNumber,
            boolean repairUsed,
            String prompt,
            String output,
            long started,
            AgentBudgetLedger ledger
    ) {
        artifacts.completeModelAttempt(logicalCallId, reservation.reservationId(), attemptNumber, true, repairUsed, true,
                estimatedTokens(prompt), estimatedTokens(output), elapsedMillis(started), null, hash(output));
        ledger.succeed(reservation.reservationId(), tokenUsage(output), clock.instant());
    }

    private com.yanyue.rag.domain.agent.budget.BudgetReservation reserve(
            AgentBudgetLedger ledger,
            String key,
            BudgetDimension operationDimension,
            int inputTokens,
            int outputTokens,
            boolean logicalCall
    ) {
        var usage = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
        if (operationDimension != null) usage.put(operationDimension, 1L);
        if (logicalCall) usage.put(BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL, 1L);
        usage.put(BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT, 1L);
        usage.put(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN, (long) Math.max(1, inputTokens));
        usage.put(BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN, (long) Math.max(1, outputTokens));
        return ledger.reserve(key, usage, clock.instant());
    }

    private Map<BudgetDimension, Long> tokenUsage(String output) {
        return Map.of(BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN,
                (long) Math.max(1, estimatedTokens(output)));
    }

    public static int estimatedTokens(String value) {
        if (value == null || value.isBlank()) return 1;
        int cjk = 0;
        for (int index = 0; index < value.length(); index++) {
            var block = Character.UnicodeScript.of(value.charAt(index));
            if (block == Character.UnicodeScript.HAN || block == Character.UnicodeScript.HIRAGANA
                    || block == Character.UnicodeScript.KATAKANA || block == Character.UnicodeScript.HANGUL) cjk++;
        }
        return Math.max(1, cjk + (value.length() - cjk + 3) / 4);
    }

    private UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private UUID goalId(String actionKey) {
        var parts = actionKey.split(":");
        if (parts.length >= 2 && "evidence-judge".equals(parts[0])) {
            try {
                return UUID.fromString(parts[1]);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (parts.length < 3 || !"deep-read".equals(parts[0])) return null;
        try {
            return UUID.fromString(parts[2]);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String phase(String actionKey) {
        var parts = actionKey.split(":");
        if (parts.length >= 2 && "evidence-judge".equals(parts[0])) {
            return "EVIDENCE_JUDGE";
        }
        return parts.length >= 2 ? parts[1] : "DEEP";
    }

    private long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private java.time.Duration remaining(AgentBudgetLedger ledger, java.time.Instant operationDeadline) {
        var effectiveDeadline = operationDeadline == null || operationDeadline.isAfter(ledger.deadline())
                ? ledger.deadline() : operationDeadline;
        var remaining = java.time.Duration.between(clock.instant(), effectiveDeadline)
                .minusSeconds(2);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalStateException("当前模型阶段 Deadline 已耗尽");
        }
        return remaining;
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
