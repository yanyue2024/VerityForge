package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.evaluation.EvaluationCase;
import com.yanyue.rag.domain.evaluation.EvaluationComparison;
import com.yanyue.rag.domain.evaluation.EvaluationDataset;
import com.yanyue.rag.domain.evaluation.EvaluationNotificationDelivery;
import com.yanyue.rag.domain.evaluation.EvaluationResult;
import com.yanyue.rag.domain.evaluation.EvaluationRun;
import com.yanyue.rag.domain.evaluation.EvaluationSchedule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

public interface EvaluationRepository {
    EvaluationDataset createDataset(UUID organizationId, String name, String description);

    List<EvaluationDataset> findDatasets(UUID organizationId);

    Optional<EvaluationDataset> findDataset(UUID organizationId, UUID datasetId);

    EvaluationCase addCase(UUID organizationId, UUID datasetId, String question, String expectedAnswer,
                           List<UUID> expectedDocumentIds, Map<String, Object> metadata);

    List<EvaluationCase> findCases(UUID organizationId, UUID datasetId);

    boolean deleteCase(UUID organizationId, UUID datasetId, UUID caseId);

    default Set<UUID> findOwnedDocumentIds(UUID organizationId, List<UUID> documentIds) {
        return Set.copyOf(documentIds);
    }

    EvaluationRun createRun(UUID organizationId, UUID datasetId);

    default EvaluationRun createRun(
            UUID organizationId,
            UUID datasetId,
            Map<String, Object> initialMetrics
    ) {
        return createRun(organizationId, datasetId);
    }

    default EvaluationComparison createComparison(
            UUID organizationId,
            UUID userId,
            UUID datasetId,
            UUID fastRunId,
            UUID deepRunId,
            String judgeMode
    ) {
        throw new UnsupportedOperationException("Evaluation comparisons are not supported");
    }

    default Optional<EvaluationComparison> findComparison(UUID organizationId, UUID comparisonId) {
        return Optional.empty();
    }

    default List<ComparisonTrend> findComparisonTrends(UUID organizationId, UUID datasetId, int limit) {
        return List.of();
    }

    default EvaluationSchedule createSchedule(
            UUID organizationId,
            UUID userId,
            UUID datasetId,
            String name,
            int cadenceMinutes,
            boolean enabled,
            Map<String, Object> request,
            Instant nextRunAt
    ) {
        return createSchedule(organizationId, userId, datasetId, name, cadenceMinutes, enabled,
                request, false, null, null, nextRunAt);
    }

    default EvaluationSchedule createSchedule(
            UUID organizationId,
            UUID userId,
            UUID datasetId,
            String name,
            int cadenceMinutes,
            boolean enabled,
            Map<String, Object> request,
            boolean webhookEnabled,
            String webhookUrl,
            String webhookSecretCiphertext,
            Instant nextRunAt
    ) {
        throw new UnsupportedOperationException("Evaluation schedules are not supported");
    }

    default EvaluationSchedule updateSchedule(
            UUID organizationId,
            UUID scheduleId,
            String name,
            int cadenceMinutes,
            boolean enabled,
            Map<String, Object> request,
            Instant nextRunAt
    ) {
        return updateSchedule(organizationId, scheduleId, name, cadenceMinutes, enabled,
                request, false, null, null, nextRunAt);
    }

    default EvaluationSchedule updateSchedule(
            UUID organizationId,
            UUID scheduleId,
            String name,
            int cadenceMinutes,
            boolean enabled,
            Map<String, Object> request,
            boolean webhookEnabled,
            String webhookUrl,
            String webhookSecretCiphertext,
            Instant nextRunAt
    ) {
        throw new UnsupportedOperationException("Evaluation schedules are not supported");
    }

    default List<EvaluationSchedule> findSchedules(UUID organizationId, UUID datasetId) {
        return List.of();
    }

    default Optional<EvaluationSchedule> findSchedule(UUID organizationId, UUID scheduleId) {
        return Optional.empty();
    }

    default boolean deleteSchedule(UUID organizationId, UUID scheduleId) {
        return false;
    }

    default List<EvaluationSchedule> claimDueSchedules(Instant now, int limit) {
        return List.of();
    }

    default void markScheduleTriggered(UUID scheduleId, UUID comparisonId, Instant triggeredAt) {
    }

    default void markScheduleFailed(UUID scheduleId, String errorMessage, Instant attemptedAt) {
    }

    default List<EvaluationNotificationDelivery> claimReadyNotifications(
            Instant now,
            Instant staleBefore,
            int limit
    ) {
        return List.of();
    }

    default void completeNotification(
            UUID deliveryId,
            int attempt,
            int responseStatus,
            String responseBody,
            Instant completedAt
    ) {
    }

    default void failNotification(
            UUID deliveryId,
            int attempt,
            boolean retry,
            Instant nextAttemptAt,
            Integer responseStatus,
            String responseBody,
            String errorMessage,
            Instant failedAt
    ) {
    }

    default List<EvaluationNotificationDelivery> findNotifications(
            UUID organizationId,
            UUID scheduleId,
            int limit
    ) {
        return List.of();
    }

    default Optional<EvaluationNotificationDelivery> findNotification(
            UUID organizationId,
            UUID deliveryId
    ) {
        return Optional.empty();
    }

    default boolean retryNotification(UUID organizationId, UUID deliveryId, Instant nextAttemptAt) {
        return false;
    }

    void markRunRunning(UUID runId);

    void completeRun(UUID runId, Map<String, Object> aggregateMetrics);

    void failRun(UUID runId, String message);

    default boolean cancelRun(UUID organizationId, UUID runId) {
        return false;
    }

    default boolean isRunCancellationRequested(UUID runId) {
        return false;
    }

    List<EvaluationRun> findRuns(UUID organizationId, UUID datasetId);

    default List<EvaluationRun> findRuns(UUID organizationId, int limit) {
        return List.of();
    }

    Optional<EvaluationRun> findRun(UUID organizationId, UUID runId);

    void saveResult(UUID runId, UUID caseId, Map<String, Object> metrics, String errorMessage);

    default void saveResult(UUID runId, UUID caseId, UUID ragRunId,
                            Map<String, Object> metrics, String errorMessage) {
        saveResult(runId, caseId, metrics, errorMessage);
    }

    List<EvaluationResult> findResults(UUID organizationId, UUID runId);

    default UUID createEvaluationConversation(UUID organizationId, UUID userId, UUID evaluationRunId) {
        throw new UnsupportedOperationException("RAG evaluation conversations are not supported");
    }

    default Optional<RagRunOutcome> findRagRunOutcome(UUID organizationId, UUID ragRunId) {
        return Optional.empty();
    }

    default List<RetrievalHit> findRagRunCandidates(UUID organizationId, UUID ragRunId) {
        return List.of();
    }

    default List<String> findRagRunAcceptedEvidenceTexts(UUID organizationId, UUID ragRunId) {
        return List.of();
    }

    default Map<String, Object> findRagRunRetrievalDiagnostics(UUID organizationId, UUID ragRunId) {
        return Map.of();
    }

    default List<CitationEvidence> findRagRunCitations(UUID organizationId, UUID ragRunId) {
        return List.of();
    }

    record CitationEvidence(
            int citationIndex,
            UUID documentId,
            UUID documentVersionId,
            UUID chunkId,
            String quote
    ) {
    }

    record RagRunOutcome(
            UUID runId,
            String status,
            String selectedMode,
            String answer,
            String noAnswerReason,
            Map<String, Object> runtimeSnapshot,
            int citationCount,
            int resolvableCitationCount,
            int effectiveVersionLeakCount,
            Instant startedAt,
            Instant completedAt,
            String errorMessage
    ) {
        public RagRunOutcome {
            runtimeSnapshot = runtimeSnapshot == null ? Map.of() : Map.copyOf(runtimeSnapshot);
        }
    }

    record ComparisonTrend(
            EvaluationComparison comparison,
            EvaluationRun fast,
            EvaluationRun deep
    ) {
    }
}
