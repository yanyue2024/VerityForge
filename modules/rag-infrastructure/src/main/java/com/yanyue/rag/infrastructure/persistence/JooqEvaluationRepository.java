package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.evaluation.EvaluationCase;
import com.yanyue.rag.domain.evaluation.EvaluationComparison;
import com.yanyue.rag.domain.evaluation.EvaluationDataset;
import com.yanyue.rag.domain.evaluation.EvaluationNotificationDelivery;
import com.yanyue.rag.domain.evaluation.EvaluationNotificationSummary;
import com.yanyue.rag.domain.evaluation.EvaluationResult;
import com.yanyue.rag.domain.evaluation.EvaluationRun;
import com.yanyue.rag.domain.evaluation.EvaluationRunStatus;
import com.yanyue.rag.domain.evaluation.EvaluationSchedule;
import com.yanyue.rag.domain.port.EvaluationRepository;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JooqEvaluationRepository implements EvaluationRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqEvaluationRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public EvaluationDataset createDataset(UUID organizationId, String name, String description) {
        var record = dsl.fetchOne("""
                INSERT INTO evaluation_dataset (id, organization_id, name, description)
                VALUES (?, ?, ?, ?)
                RETURNING id, organization_id, name, description, created_at
                """, UUID.randomUUID(), organizationId, name, description);
        return dataset(record);
    }

    @Override
    public List<EvaluationDataset> findDatasets(UUID organizationId) {
        return dsl.fetch("""
                SELECT id, organization_id, name, description, created_at
                FROM evaluation_dataset
                WHERE organization_id = ?
                ORDER BY created_at DESC
                """, organizationId).map(this::dataset);
    }

    @Override
    public Optional<EvaluationDataset> findDataset(UUID organizationId, UUID datasetId) {
        return dsl.fetchOptional("""
                SELECT id, organization_id, name, description, created_at
                FROM evaluation_dataset
                WHERE organization_id = ? AND id = ?
                """, organizationId, datasetId).map(this::dataset);
    }

    @Override
    public EvaluationCase addCase(UUID organizationId, UUID datasetId, String question, String expectedAnswer,
                                  List<UUID> expectedDocumentIds, Map<String, Object> metadata) {
        var record = dsl.fetchOptional("""
                INSERT INTO evaluation_case
                    (id, dataset_id, question, expected_answer, expected_document_ids, metadata)
                SELECT ?, d.id, ?, ?, ?, ?::jsonb
                FROM evaluation_dataset d
                WHERE d.id = ? AND d.organization_id = ?
                RETURNING id, dataset_id, question, expected_answer, expected_document_ids, position,
                          metadata::text AS metadata
                """, UUID.randomUUID(), question, blankToNull(expectedAnswer),
                expectedDocumentIds.toArray(UUID[]::new), json(metadata), datasetId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation dataset not found"));
        return evaluationCase(record);
    }

    @Override
    public List<EvaluationCase> findCases(UUID organizationId, UUID datasetId) {
        return dsl.fetch("""
                SELECT c.id, c.dataset_id, c.question, c.expected_answer, c.expected_document_ids, c.position,
                       c.metadata::text AS metadata
                FROM evaluation_case c
                JOIN evaluation_dataset d ON d.id = c.dataset_id
                WHERE d.organization_id = ? AND c.dataset_id = ?
                ORDER BY c.position, c.id
                """, organizationId, datasetId).map(this::evaluationCase);
    }

    @Override
    public boolean deleteCase(UUID organizationId, UUID datasetId, UUID caseId) {
        var deleted = dsl.execute("""
                DELETE FROM evaluation_case c
                USING evaluation_dataset d
                WHERE c.dataset_id = d.id
                  AND c.id = ?
                  AND c.dataset_id = ?
                  AND d.organization_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM evaluation_result result
                      WHERE result.evaluation_case_id = c.id
                  )
                """, caseId, datasetId, organizationId) > 0;
        if (deleted) return true;
        var exists = dsl.fetchExists(dsl.selectOne()
                .from("evaluation_case c")
                .join("evaluation_dataset d").on("d.id = c.dataset_id")
                .where(org.jooq.impl.DSL.field("c.id").eq(caseId))
                .and(org.jooq.impl.DSL.field("c.dataset_id").eq(datasetId))
                .and(org.jooq.impl.DSL.field("d.organization_id").eq(organizationId)));
        if (exists) {
            throw new IllegalArgumentException("Evaluation case has run history and cannot be deleted");
        }
        return false;
    }

    @Override
    public Set<UUID> findOwnedDocumentIds(UUID organizationId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) return Set.of();
        return Set.copyOf(dsl.fetch("""
                SELECT id
                FROM document
                WHERE organization_id = ? AND id = ANY(?::uuid[])
                """, organizationId, documentIds.toArray(UUID[]::new)).getValues("id", UUID.class));
    }

    @Override
    public EvaluationRun createRun(UUID organizationId, UUID datasetId) {
        return createRun(organizationId, datasetId, Map.of());
    }

    @Override
    public EvaluationRun createRun(
            UUID organizationId,
            UUID datasetId,
            Map<String, Object> initialMetrics
    ) {
        var record = dsl.fetchOptional("""
                INSERT INTO evaluation_run (id, dataset_id, status, aggregate_metrics)
                SELECT ?, d.id, 'QUEUED', ?::jsonb
                FROM evaluation_dataset d
                WHERE d.id = ? AND d.organization_id = ?
                RETURNING id, dataset_id, status, aggregate_metrics::text AS aggregate_metrics,
                          started_at, completed_at, created_at
                """, UUID.randomUUID(), json(initialMetrics), datasetId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation dataset not found"));
        return run(record);
    }

    @Override
    public EvaluationComparison createComparison(
            UUID organizationId,
            UUID userId,
            UUID datasetId,
            UUID fastRunId,
            UUID deepRunId,
            String judgeMode
    ) {
        var record = dsl.fetchOptional("""
                INSERT INTO evaluation_comparison
                    (id, dataset_id, fast_run_id, deep_run_id, judge_mode, created_by)
                SELECT ?, d.id, fast.id, deep.id, ?, u.id
                FROM evaluation_dataset d
                JOIN evaluation_run fast ON fast.dataset_id = d.id AND fast.id = ?
                JOIN evaluation_run deep ON deep.dataset_id = d.id AND deep.id = ?
                JOIN app_user u ON u.organization_id = d.organization_id AND u.id = ? AND u.enabled = true
                WHERE d.id = ? AND d.organization_id = ?
                RETURNING id, dataset_id, fast_run_id, deep_run_id, judge_mode, created_at
                """, UUID.randomUUID(), judgeMode, fastRunId, deepRunId, userId, datasetId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation comparison inputs are invalid"));
        return comparison(record);
    }

    @Override
    public Optional<EvaluationComparison> findComparison(UUID organizationId, UUID comparisonId) {
        return dsl.fetchOptional("""
                SELECT comparison.id, comparison.dataset_id, comparison.fast_run_id,
                       comparison.deep_run_id, comparison.judge_mode, comparison.created_at
                FROM evaluation_comparison comparison
                JOIN evaluation_dataset dataset ON dataset.id = comparison.dataset_id
                WHERE comparison.id = ? AND dataset.organization_id = ?
                """, comparisonId, organizationId).map(this::comparison);
    }

    @Override
    public List<ComparisonTrend> findComparisonTrends(UUID organizationId, UUID datasetId, int limit) {
        return dsl.fetch("""
                SELECT comparison.id, comparison.dataset_id, comparison.fast_run_id,
                       comparison.deep_run_id, comparison.judge_mode, comparison.created_at,
                       fast.id AS fast_id, fast.dataset_id AS fast_dataset_id,
                       fast.status AS fast_status,
                       fast.aggregate_metrics::text AS fast_aggregate_metrics,
                       fast.started_at AS fast_started_at, fast.completed_at AS fast_completed_at,
                       fast.created_at AS fast_created_at,
                       deep.id AS deep_id, deep.dataset_id AS deep_dataset_id,
                       deep.status AS deep_status,
                       deep.aggregate_metrics::text AS deep_aggregate_metrics,
                       deep.started_at AS deep_started_at, deep.completed_at AS deep_completed_at,
                       deep.created_at AS deep_created_at
                FROM evaluation_comparison comparison
                JOIN evaluation_dataset dataset ON dataset.id = comparison.dataset_id
                JOIN evaluation_run fast ON fast.id = comparison.fast_run_id
                JOIN evaluation_run deep ON deep.id = comparison.deep_run_id
                WHERE dataset.organization_id = ? AND dataset.id = ?
                ORDER BY comparison.created_at DESC
                LIMIT ?
                """, organizationId, datasetId, Math.max(1, Math.min(limit, 100)))
                .map(record -> new ComparisonTrend(
                        comparison(record), prefixedRun(record, "fast_"), prefixedRun(record, "deep_")));
    }

    @Override
    public EvaluationSchedule createSchedule(
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
            java.time.Instant nextRunAt
    ) {
        var record = dsl.fetchOptional("""
                INSERT INTO evaluation_schedule
                    (id, organization_id, dataset_id, created_by, name, cadence_minutes,
                     enabled, request, webhook_enabled, webhook_url, webhook_secret_ciphertext, next_run_at)
                SELECT ?, dataset.organization_id, dataset.id, app_user.id, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::timestamptz
                FROM evaluation_dataset dataset
                JOIN app_user ON app_user.id = ?
                  AND app_user.organization_id = dataset.organization_id
                  AND app_user.enabled = true
                WHERE dataset.id = ? AND dataset.organization_id = ?
                RETURNING id, organization_id, dataset_id, created_by, name, cadence_minutes,
                          enabled, request::text AS request, webhook_enabled, webhook_url,
                          webhook_secret_ciphertext, next_run_at, last_run_at,
                          last_comparison_id, last_error, created_at, updated_at,
                          NULL::uuid AS last_notification_id,
                          NULL::uuid AS last_notification_comparison_id,
                          NULL::text AS last_notification_status,
                          NULL::integer AS last_notification_attempt,
                          NULL::integer AS last_notification_max_attempts,
                          NULL::integer AS last_notification_response_status,
                          NULL::text AS last_notification_error_message,
                          NULL::timestamptz AS last_notification_updated_at
                """, UUID.randomUUID(), name, cadenceMinutes, enabled, json(request), webhookEnabled,
                webhookUrl, webhookSecretCiphertext, offset(nextRunAt),
                userId, datasetId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation schedule inputs are invalid"));
        return schedule(record);
    }

    @Override
    public EvaluationSchedule updateSchedule(
            UUID organizationId,
            UUID scheduleId,
            String name,
            int cadenceMinutes,
            boolean enabled,
            Map<String, Object> request,
            boolean webhookEnabled,
            String webhookUrl,
            String webhookSecretCiphertext,
            java.time.Instant nextRunAt
    ) {
        var record = dsl.fetchOptional("""
                UPDATE evaluation_schedule
                SET name = ?, cadence_minutes = ?, enabled = ?, request = ?::jsonb,
                    webhook_enabled = ?, webhook_url = ?, webhook_secret_ciphertext = ?,
                    next_run_at = ?::timestamptz, last_error = NULL, updated_at = now()
                WHERE id = ? AND organization_id = ?
                RETURNING id, organization_id, dataset_id, created_by, name, cadence_minutes,
                          enabled, request::text AS request, webhook_enabled, webhook_url,
                          webhook_secret_ciphertext, next_run_at, last_run_at,
                          last_comparison_id, last_error, created_at, updated_at,
                          NULL::uuid AS last_notification_id,
                          NULL::uuid AS last_notification_comparison_id,
                          NULL::text AS last_notification_status,
                          NULL::integer AS last_notification_attempt,
                          NULL::integer AS last_notification_max_attempts,
                          NULL::integer AS last_notification_response_status,
                          NULL::text AS last_notification_error_message,
                          NULL::timestamptz AS last_notification_updated_at
                """, name, cadenceMinutes, enabled, json(request), webhookEnabled, webhookUrl,
                webhookSecretCiphertext, offset(nextRunAt), scheduleId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation schedule not found"));
        return schedule(record);
    }

    @Override
    public List<EvaluationSchedule> findSchedules(UUID organizationId, UUID datasetId) {
        return dsl.fetch("""
                SELECT schedule.id, schedule.organization_id, schedule.dataset_id, schedule.created_by,
                       schedule.name, schedule.cadence_minutes, schedule.enabled,
                       schedule.request::text AS request, schedule.webhook_enabled, schedule.webhook_url,
                       schedule.webhook_secret_ciphertext, schedule.next_run_at, schedule.last_run_at,
                       schedule.last_comparison_id, schedule.last_error, schedule.created_at, schedule.updated_at,
                       notification.id AS last_notification_id,
                       notification.comparison_id AS last_notification_comparison_id,
                       notification.status AS last_notification_status,
                       notification.attempt AS last_notification_attempt,
                       notification.max_attempts AS last_notification_max_attempts,
                       notification.response_status AS last_notification_response_status,
                       notification.error_message AS last_notification_error_message,
                       notification.updated_at AS last_notification_updated_at
                FROM evaluation_schedule schedule
                LEFT JOIN LATERAL (
                    SELECT delivery.* FROM evaluation_notification_delivery delivery
                    WHERE delivery.schedule_id = schedule.id
                    ORDER BY delivery.created_at DESC LIMIT 1
                ) notification ON true
                WHERE schedule.organization_id = ? AND schedule.dataset_id = ?
                ORDER BY schedule.created_at DESC
                """, organizationId, datasetId).map(this::schedule);
    }

    @Override
    public Optional<EvaluationSchedule> findSchedule(UUID organizationId, UUID scheduleId) {
        return dsl.fetchOptional("""
                SELECT schedule.id, schedule.organization_id, schedule.dataset_id, schedule.created_by,
                       schedule.name, schedule.cadence_minutes, schedule.enabled,
                       schedule.request::text AS request, schedule.webhook_enabled, schedule.webhook_url,
                       schedule.webhook_secret_ciphertext, schedule.next_run_at, schedule.last_run_at,
                       schedule.last_comparison_id, schedule.last_error, schedule.created_at, schedule.updated_at,
                       notification.id AS last_notification_id,
                       notification.comparison_id AS last_notification_comparison_id,
                       notification.status AS last_notification_status,
                       notification.attempt AS last_notification_attempt,
                       notification.max_attempts AS last_notification_max_attempts,
                       notification.response_status AS last_notification_response_status,
                       notification.error_message AS last_notification_error_message,
                       notification.updated_at AS last_notification_updated_at
                FROM evaluation_schedule schedule
                LEFT JOIN LATERAL (
                    SELECT delivery.* FROM evaluation_notification_delivery delivery
                    WHERE delivery.schedule_id = schedule.id
                    ORDER BY delivery.created_at DESC LIMIT 1
                ) notification ON true
                WHERE schedule.organization_id = ? AND schedule.id = ?
                """, organizationId, scheduleId).map(this::schedule);
    }

    @Override
    public boolean deleteSchedule(UUID organizationId, UUID scheduleId) {
        return dsl.execute("""
                DELETE FROM evaluation_schedule
                WHERE organization_id = ? AND id = ?
                """, organizationId, scheduleId) == 1;
    }

    @Override
    public List<EvaluationSchedule> claimDueSchedules(java.time.Instant now, int limit) {
        var claimedAt = offset(now);
        return dsl.fetch("""
                WITH due AS (
                    SELECT id
                    FROM evaluation_schedule schedule
                    WHERE schedule.enabled = true
                      AND schedule.next_run_at <= ?::timestamptz
                      AND NOT EXISTS (
                          SELECT 1
                          FROM evaluation_comparison comparison
                          JOIN evaluation_run active_run
                            ON active_run.id IN (comparison.fast_run_id, comparison.deep_run_id)
                          WHERE comparison.id = schedule.last_comparison_id
                            AND active_run.status IN ('QUEUED', 'RUNNING')
                      )
                    ORDER BY next_run_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE evaluation_schedule schedule
                SET last_run_at = ?::timestamptz,
                    next_run_at = ?::timestamptz + make_interval(mins => schedule.cadence_minutes),
                    last_error = NULL,
                    updated_at = now()
                FROM due
                WHERE schedule.id = due.id
                RETURNING schedule.id, schedule.organization_id, schedule.dataset_id,
                          schedule.created_by, schedule.name, schedule.cadence_minutes,
                          schedule.enabled, schedule.request::text AS request,
                          schedule.webhook_enabled, schedule.webhook_url,
                          schedule.webhook_secret_ciphertext,
                          schedule.next_run_at, schedule.last_run_at,
                          schedule.last_comparison_id, schedule.last_error,
                          schedule.created_at, schedule.updated_at,
                          NULL::uuid AS last_notification_id,
                          NULL::uuid AS last_notification_comparison_id,
                          NULL::text AS last_notification_status,
                          NULL::integer AS last_notification_attempt,
                          NULL::integer AS last_notification_max_attempts,
                          NULL::integer AS last_notification_response_status,
                          NULL::text AS last_notification_error_message,
                          NULL::timestamptz AS last_notification_updated_at
                """, claimedAt, Math.max(1, Math.min(limit, 50)), claimedAt, claimedAt)
                .map(this::schedule);
    }

    @Override
    @Transactional
    public void markScheduleTriggered(UUID scheduleId, UUID comparisonId, java.time.Instant triggeredAt) {
        dsl.execute("""
                UPDATE evaluation_schedule
                SET last_run_at = ?::timestamptz, last_comparison_id = ?, last_error = NULL, updated_at = now()
                WHERE id = ?
                """, offset(triggeredAt), comparisonId, scheduleId);
        dsl.execute("""
                INSERT INTO evaluation_notification_delivery
                    (id, organization_id, schedule_id, comparison_id, dataset_id,
                     schedule_name, dataset_name, webhook_url, webhook_secret_ciphertext,
                     status, next_attempt_at)
                SELECT ?, schedule.organization_id, schedule.id, comparison.id, dataset.id,
                       schedule.name, dataset.name, schedule.webhook_url,
                       schedule.webhook_secret_ciphertext, 'WAITING', ?::timestamptz
                FROM evaluation_schedule schedule
                JOIN evaluation_dataset dataset ON dataset.id = schedule.dataset_id
                JOIN evaluation_comparison comparison
                  ON comparison.id = ? AND comparison.dataset_id = schedule.dataset_id
                WHERE schedule.id = ? AND schedule.webhook_enabled = true
                  AND schedule.webhook_url IS NOT NULL
                  AND schedule.webhook_secret_ciphertext IS NOT NULL
                ON CONFLICT (comparison_id) DO NOTHING
                """, UUID.randomUUID(), offset(triggeredAt), comparisonId, scheduleId);
    }

    @Override
    public void markScheduleFailed(UUID scheduleId, String errorMessage, java.time.Instant attemptedAt) {
        dsl.execute("""
                UPDATE evaluation_schedule
                SET last_run_at = ?::timestamptz, last_error = ?, updated_at = now()
                WHERE id = ?
                """, offset(attemptedAt), errorMessage, scheduleId);
    }

    @Override
    @Transactional
    public List<EvaluationNotificationDelivery> claimReadyNotifications(
            java.time.Instant now,
            java.time.Instant staleBefore,
            int limit
    ) {
        dsl.execute("""
                UPDATE evaluation_notification_delivery
                SET status = 'FAILED', claimed_at = NULL, updated_at = now(),
                    error_message = COALESCE(error_message, 'Delivery lease expired at the retry limit')
                WHERE status = 'DELIVERING' AND claimed_at < ?::timestamptz
                  AND attempt >= max_attempts
                """, offset(staleBefore));
        return dsl.fetch("""
                WITH ready AS (
                    SELECT delivery.id
                    FROM evaluation_notification_delivery delivery
                    JOIN evaluation_comparison comparison ON comparison.id = delivery.comparison_id
                    JOIN evaluation_run fast ON fast.id = comparison.fast_run_id
                    JOIN evaluation_run deep ON deep.id = comparison.deep_run_id
                    WHERE delivery.attempt < delivery.max_attempts
                      AND fast.status IN ('COMPLETED', 'FAILED')
                      AND deep.status IN ('COMPLETED', 'FAILED')
                      AND (
                          (delivery.status IN ('WAITING', 'RETRY')
                           AND delivery.next_attempt_at <= ?::timestamptz)
                          OR (delivery.status = 'DELIVERING'
                              AND delivery.claimed_at < ?::timestamptz)
                      )
                    ORDER BY delivery.next_attempt_at, delivery.id
                    LIMIT ?
                    FOR UPDATE OF delivery SKIP LOCKED
                ), claimed AS (
                    UPDATE evaluation_notification_delivery delivery
                    SET status = 'DELIVERING', attempt = delivery.attempt + 1,
                        claimed_at = ?::timestamptz, updated_at = now()
                    FROM ready
                    WHERE delivery.id = ready.id
                    RETURNING delivery.*
                )
                SELECT claimed.id, claimed.organization_id, claimed.schedule_id,
                       claimed.comparison_id, claimed.dataset_id, claimed.schedule_name,
                       claimed.dataset_name, claimed.webhook_url, claimed.webhook_secret_ciphertext,
                       claimed.status, claimed.attempt, claimed.max_attempts, claimed.response_status,
                       claimed.response_body, claimed.error_message, claimed.next_attempt_at,
                       claimed.delivered_at, claimed.created_at, claimed.updated_at,
                       fast.id AS fast_id, fast.dataset_id AS fast_dataset_id,
                       fast.status AS fast_status,
                       fast.aggregate_metrics::text AS fast_aggregate_metrics,
                       fast.started_at AS fast_started_at, fast.completed_at AS fast_completed_at,
                       fast.created_at AS fast_created_at,
                       deep.id AS deep_id, deep.dataset_id AS deep_dataset_id,
                       deep.status AS deep_status,
                       deep.aggregate_metrics::text AS deep_aggregate_metrics,
                       deep.started_at AS deep_started_at, deep.completed_at AS deep_completed_at,
                       deep.created_at AS deep_created_at
                FROM claimed
                JOIN evaluation_comparison comparison ON comparison.id = claimed.comparison_id
                JOIN evaluation_run fast ON fast.id = comparison.fast_run_id
                JOIN evaluation_run deep ON deep.id = comparison.deep_run_id
                ORDER BY claimed.next_attempt_at, claimed.id
                """, offset(now), offset(staleBefore), Math.max(1, Math.min(limit, 50)), offset(now))
                .map(record -> notification(record, true));
    }

    @Override
    public void completeNotification(
            UUID deliveryId,
            int attempt,
            int responseStatus,
            String responseBody,
            java.time.Instant completedAt
    ) {
        dsl.execute("""
                UPDATE evaluation_notification_delivery
                SET status = 'SUCCEEDED', response_status = ?, response_body = ?,
                    error_message = NULL, claimed_at = NULL, delivered_at = ?::timestamptz,
                    updated_at = now()
                WHERE id = ? AND status = 'DELIVERING' AND attempt = ?
                """, responseStatus, responseBody, offset(completedAt), deliveryId, attempt);
    }

    @Override
    public void failNotification(
            UUID deliveryId,
            int attempt,
            boolean retry,
            java.time.Instant nextAttemptAt,
            Integer responseStatus,
            String responseBody,
            String errorMessage,
            java.time.Instant failedAt
    ) {
        dsl.execute("""
                UPDATE evaluation_notification_delivery
                SET status = ?, next_attempt_at = ?::timestamptz, response_status = ?,
                    response_body = ?, error_message = ?, claimed_at = NULL,
                    delivered_at = CASE WHEN ? THEN NULL ELSE ?::timestamptz END,
                    updated_at = now()
                WHERE id = ? AND status = 'DELIVERING' AND attempt = ?
                """, retry ? "RETRY" : "FAILED", offset(nextAttemptAt), responseStatus,
                responseBody, errorMessage, retry, offset(failedAt), deliveryId, attempt);
    }

    @Override
    public List<EvaluationNotificationDelivery> findNotifications(
            UUID organizationId,
            UUID scheduleId,
            int limit
    ) {
        return dsl.fetch("""
                SELECT delivery.id, delivery.organization_id, delivery.schedule_id,
                       delivery.comparison_id, delivery.dataset_id, delivery.schedule_name,
                       delivery.dataset_name, delivery.webhook_url, delivery.webhook_secret_ciphertext,
                       delivery.status, delivery.attempt, delivery.max_attempts,
                       delivery.response_status, delivery.response_body, delivery.error_message,
                       delivery.next_attempt_at, delivery.delivered_at, delivery.created_at, delivery.updated_at
                FROM evaluation_notification_delivery delivery
                WHERE delivery.organization_id = ? AND delivery.schedule_id = ?
                ORDER BY delivery.created_at DESC
                LIMIT ?
                """, organizationId, scheduleId, Math.max(1, Math.min(limit, 100)))
                .map(record -> notification(record, false));
    }

    @Override
    public Optional<EvaluationNotificationDelivery> findNotification(UUID organizationId, UUID deliveryId) {
        return dsl.fetchOptional("""
                SELECT delivery.id, delivery.organization_id, delivery.schedule_id,
                       delivery.comparison_id, delivery.dataset_id, delivery.schedule_name,
                       delivery.dataset_name, delivery.webhook_url, delivery.webhook_secret_ciphertext,
                       delivery.status, delivery.attempt, delivery.max_attempts,
                       delivery.response_status, delivery.response_body, delivery.error_message,
                       delivery.next_attempt_at, delivery.delivered_at, delivery.created_at, delivery.updated_at
                FROM evaluation_notification_delivery delivery
                WHERE delivery.organization_id = ? AND delivery.id = ?
                """, organizationId, deliveryId).map(record -> notification(record, false));
    }

    @Override
    public boolean retryNotification(UUID organizationId, UUID deliveryId, java.time.Instant nextAttemptAt) {
        return dsl.execute("""
                UPDATE evaluation_notification_delivery
                SET status = 'RETRY', attempt = 0, next_attempt_at = ?::timestamptz,
                    claimed_at = NULL, delivered_at = NULL, response_status = NULL,
                    response_body = NULL, error_message = NULL, updated_at = now()
                WHERE id = ? AND organization_id = ? AND status = 'FAILED'
                """, offset(nextAttemptAt), deliveryId, organizationId) == 1;
    }

    @Override
    public void markRunRunning(UUID runId) {
        dsl.execute("""
                UPDATE evaluation_run
                SET status = 'RUNNING', started_at = now(), completed_at = NULL
                WHERE id = ? AND status = 'QUEUED' AND cancellation_requested = false
                """, runId);
    }

    @Override
    public void completeRun(UUID runId, Map<String, Object> aggregateMetrics) {
        dsl.execute("""
                UPDATE evaluation_run
                SET status = 'COMPLETED', aggregate_metrics = ?::jsonb, completed_at = now()
                WHERE id = ? AND status = 'RUNNING' AND cancellation_requested = false
                """, json(aggregateMetrics), runId);
    }

    @Override
    public void failRun(UUID runId, String message) {
        dsl.execute("""
                UPDATE evaluation_run
                SET status = 'FAILED',
                    aggregate_metrics = aggregate_metrics || ?::jsonb,
                    completed_at = now()
                WHERE id = ? AND status <> 'CANCELLED'
                """, json(Map.of("error", message == null ? "Evaluation failed" : message)), runId);
    }

    @Override
    public boolean cancelRun(UUID organizationId, UUID runId) {
        return dsl.execute("""
                UPDATE evaluation_run run
                SET status = 'CANCELLED', cancellation_requested = true,
                    aggregate_metrics = run.aggregate_metrics || jsonb_build_object(
                        'cancelled', true, 'cancelReason', 'cancelled-by-user'),
                    completed_at = now()
                FROM evaluation_dataset dataset
                WHERE run.id = ? AND run.dataset_id = dataset.id
                  AND dataset.organization_id = ?
                  AND run.status IN ('QUEUED', 'RUNNING')
                """, runId, organizationId) == 1;
    }

    @Override
    public boolean isRunCancellationRequested(UUID runId) {
        var record = dsl.fetchOne("""
                SELECT cancellation_requested FROM evaluation_run WHERE id = ?
                """, runId);
        return record != null && Boolean.TRUE.equals(record.get(0, Boolean.class));
    }

    @Override
    public List<EvaluationRun> findRuns(UUID organizationId, UUID datasetId) {
        return dsl.fetch("""
                SELECT r.id, r.dataset_id, r.status, r.aggregate_metrics::text AS aggregate_metrics,
                       r.started_at, r.completed_at, r.created_at
                FROM evaluation_run r
                JOIN evaluation_dataset d ON d.id = r.dataset_id
                WHERE d.organization_id = ? AND r.dataset_id = ?
                ORDER BY r.created_at DESC
                LIMIT 50
                """, organizationId, datasetId).map(this::run);
    }

    @Override
    public List<EvaluationRun> findRuns(UUID organizationId, int limit) {
        return dsl.fetch("""
                SELECT r.id, r.dataset_id, r.status, r.aggregate_metrics::text AS aggregate_metrics,
                       r.started_at, r.completed_at, r.created_at
                FROM evaluation_run r
                JOIN evaluation_dataset d ON d.id = r.dataset_id
                WHERE d.organization_id = ?
                ORDER BY r.created_at DESC
                LIMIT ?
                """, organizationId, Math.max(1, Math.min(limit, 200))).map(this::run);
    }

    @Override
    public Optional<EvaluationRun> findRun(UUID organizationId, UUID runId) {
        return dsl.fetchOptional("""
                SELECT r.id, r.dataset_id, r.status, r.aggregate_metrics::text AS aggregate_metrics,
                       r.started_at, r.completed_at, r.created_at
                FROM evaluation_run r
                JOIN evaluation_dataset d ON d.id = r.dataset_id
                WHERE d.organization_id = ? AND r.id = ?
                """, organizationId, runId).map(this::run);
    }

    @Override
    public void saveResult(UUID runId, UUID caseId, Map<String, Object> metrics, String errorMessage) {
        saveResult(runId, caseId, null, metrics, errorMessage);
    }

    @Override
    public void saveResult(UUID runId, UUID caseId, UUID ragRunId,
                           Map<String, Object> metrics, String errorMessage) {
        dsl.execute("""
                INSERT INTO evaluation_result
                    (id, evaluation_run_id, evaluation_case_id, rag_run_id, metrics, error_message)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (evaluation_run_id, evaluation_case_id) DO UPDATE
                SET rag_run_id = EXCLUDED.rag_run_id,
                    metrics = EXCLUDED.metrics,
                    error_message = EXCLUDED.error_message,
                    created_at = now()
                """, UUID.randomUUID(), runId, caseId, ragRunId, json(metrics), errorMessage);
    }

    @Override
    public UUID createEvaluationConversation(UUID organizationId, UUID userId, UUID evaluationRunId) {
        var conversationId = UUID.randomUUID();
        var inserted = dsl.execute("""
                INSERT INTO conversation (id, organization_id, title, created_by, conversation_kind)
                SELECT ?, o.id, ?, u.id, 'EVALUATION'
                FROM organization o
                JOIN app_user u ON u.organization_id = o.id AND u.id = ? AND u.enabled = true
                WHERE o.id = ?
                """, conversationId, "Evaluation " + evaluationRunId.toString().substring(0, 8),
                userId, organizationId);
        if (inserted != 1) throw new IllegalArgumentException("Evaluation user or organization not found");
        return conversationId;
    }

    @Override
    public Optional<RagRunOutcome> findRagRunOutcome(UUID organizationId, UUID ragRunId) {
        return dsl.fetchOptional("""
                SELECT r.id, r.status, r.selected_mode, r.no_answer_reason,
                       r.runtime_snapshot::text AS runtime_snapshot, r.started_at, r.completed_at, r.error_message,
                       COALESCE((
                           SELECT m.content
                           FROM conversation_message m
                           WHERE m.role = 'assistant' AND m.metadata ->> 'runId' = r.id::text
                           ORDER BY m.created_at DESC LIMIT 1
                       ), '') AS answer,
                       (SELECT count(*) FROM citation c WHERE c.run_id = r.id) AS citation_count,
                       (SELECT count(*)
                        FROM citation c
                        LEFT JOIN document d ON d.id = c.document_id
                        LEFT JOIN document_version dv ON dv.id = c.document_version_id
                        LEFT JOIN chunk ch ON ch.id = c.chunk_id
                        WHERE c.run_id = r.id AND d.id IS NOT NULL AND dv.id IS NOT NULL AND ch.id IS NOT NULL
                       ) AS resolvable_citation_count,
                       (SELECT count(*)
                        FROM citation c
                        JOIN document d ON d.id = c.document_id
                        JOIN document_version dv ON dv.id = c.document_version_id
                        WHERE c.run_id = r.id AND NOT (
                            d.current_version_id = dv.id AND d.status = 'ACTIVE' AND dv.status = 'PUBLISHED'
                            AND (dv.valid_from IS NULL OR dv.valid_from <= r.completed_at)
                            AND (dv.valid_to IS NULL OR dv.valid_to > r.completed_at)
                        )
                       ) AS effective_version_leak_count
                FROM rag_run r
                WHERE r.id = ? AND r.organization_id = ?
                """, ragRunId, organizationId).map(record -> new RagRunOutcome(
                record.get("id", UUID.class), record.get("status", String.class),
                record.get("selected_mode", String.class), record.get("answer", String.class),
                record.get("no_answer_reason", String.class), map(record.get("runtime_snapshot", String.class)),
                count(record, "citation_count"),
                count(record, "resolvable_citation_count"),
                count(record, "effective_version_leak_count"),
                nullableInstant(record, "started_at"), nullableInstant(record, "completed_at"),
                record.get("error_message", String.class)
        ));
    }

    @Override
    public List<RetrievalHit> findRagRunCandidates(UUID organizationId, UUID ragRunId) {
        var pipelineVersion = dsl.fetchOptional("""
                SELECT pipeline_version FROM rag_run WHERE id = ? AND organization_id = ?
                """, ragRunId, organizationId)
                .map(record -> record.get("pipeline_version", String.class)).orElse("");
        if (java.util.Set.of("agentic-rag-v7", "agentic-rag-v8", "deep-rag-final")
                .contains(pipelineVersion)) {
            return findAgentV7Candidates(organizationId, ragRunId);
        }
        if ("agentic-rag-v5".equals(pipelineVersion)) {
            return findAgentV5Candidates(organizationId, ragRunId);
        }
        return dsl.fetch("""
                WITH combined AS (
                    SELECT rc.chunk_id, COALESCE(rc.rerank_score, rc.rrf_score, 0) AS score,
                           rc.retrieval_sources, 0 AS evidence_priority,
                           NULL::integer AS source_start, NULL::integer AS source_end,
                           NULL::bigint AS deep_read_order, NULL::bigint AS discovery_order,
                           rc.created_at
                    FROM retrieval_candidate rc
                    JOIN rag_run r ON r.id = rc.run_id
                    WHERE rc.run_id = ? AND r.organization_id = ?
                    UNION ALL
                    SELECT candidate.chunk_id, COALESCE(candidate.rerank_score, candidate.score) AS score,
                           ARRAY[lower(candidate.retrieval_source)]::text[] AS retrieval_sources,
                           0 AS evidence_priority, NULL::integer AS source_start, NULL::integer AS source_end,
                           NULL::bigint AS deep_read_order, candidate.candidate_rank::bigint AS discovery_order,
                           candidate.created_at
                    FROM retrieval_query_candidate candidate
                    JOIN rag_run r ON r.id = candidate.run_id
                    WHERE candidate.run_id = ? AND r.organization_id = ?
                    UNION ALL
                    SELECT e.chunk_id, e.retrieval_score AS score, e.retrieval_sources,
                           CASE WHEN e.deep_read THEN 1 ELSE 0 END AS evidence_priority,
                           e.source_start, e.source_end, NULL::bigint, NULL::bigint, e.created_at
                    FROM evidence_item e
                    JOIN rag_run r ON r.id = e.run_id
                    WHERE e.run_id = ? AND r.organization_id = ?
                    UNION ALL
                    SELECT reference.chunk_id, COALESCE(reference.score, 0) AS score,
                           reference.sources AS retrieval_sources,
                           CASE WHEN reference.deep_read THEN 2 ELSE 1 END AS evidence_priority,
                           reference.source_start, reference.source_end,
                           reference.first_deep_read_order, reference.first_discovery_order,
                           reference.created_at
                    FROM agent_knowledge_reference reference
                    JOIN rag_run r ON r.id = reference.run_id
                    JOIN document d ON d.id = reference.document_id
                    JOIN document_version dv ON dv.id = reference.document_version_id
                    WHERE reference.run_id = ? AND r.organization_id = ?
                      AND d.current_version_id = dv.id AND d.status = 'ACTIVE' AND dv.status = 'PUBLISHED'
                ), selected AS (
                    SELECT DISTINCT ON (chunk_id) *
                    FROM combined
                    ORDER BY chunk_id, evidence_priority DESC, deep_read_order NULLS LAST,
                             discovery_order NULLS LAST, score DESC, created_at
                )
                SELECT c.id AS chunk_id, c.parent_chunk_id, d.id AS document_id,
                       dv.id AS document_version_id, d.title AS document_title, c.chunk_text,
                       selected.score, selected.retrieval_sources,
                       (SELECT min(db.page_number)
                        FROM document_block db WHERE db.id = ANY(c.source_block_ids)) AS page_number,
                       selected.source_start, selected.source_end
                FROM selected
                JOIN chunk c ON c.id = selected.chunk_id
                JOIN document_version dv ON dv.id = c.document_version_id
                JOIN document d ON d.id = dv.document_id
                ORDER BY selected.evidence_priority DESC, selected.deep_read_order NULLS LAST,
                         selected.discovery_order NULLS LAST, selected.score DESC,
                         selected.created_at, c.id
                LIMIT 40
                """, ragRunId, organizationId, ragRunId, organizationId, ragRunId, organizationId,
                ragRunId, organizationId).map(record -> new RetrievalHit(
                record.get("chunk_id", UUID.class), record.get("parent_chunk_id", UUID.class),
                record.get("document_id", UUID.class), record.get("document_version_id", UUID.class),
                record.get("document_title", String.class), record.get("chunk_text", String.class),
                record.get("score", Double.class),
                Arrays.asList(record.get("retrieval_sources", String[].class)),
                record.get("page_number", Integer.class), record.get("source_start", Integer.class),
                record.get("source_end", Integer.class)
        ));
    }

    private List<RetrievalHit> findAgentV5Candidates(UUID organizationId, UUID ragRunId) {
        return dsl.fetch("""
                WITH phase_candidates AS (
                    SELECT candidate.run_id, candidate.goal_id, candidate.goal_order, candidate.phase,
                           candidate.chunk_id, candidate.document_id, candidate.document_version_id,
                           COALESCE(candidate.rerank_rank, candidate.rrf_rank) AS final_rank,
                           COALESCE(candidate.rerank_score, candidate.rrf_score, 0) AS final_score
                    FROM agent_goal_ranked_candidate candidate
                    JOIN rag_run run ON run.id = candidate.run_id
                    WHERE candidate.run_id = ? AND run.organization_id = ?
                      AND COALESCE(candidate.rerank_rank, candidate.rrf_rank) IS NOT NULL
                ), goal_fused_chunks AS (
                    SELECT candidate.run_id, candidate.goal_id, min(candidate.goal_order) AS goal_order,
                           candidate.chunk_id, candidate.document_id, candidate.document_version_id,
                           sum(1.0 / (60.0 + candidate.final_rank)) AS fusion_score,
                           min(candidate.final_rank) AS best_phase_rank,
                           max(candidate.final_score) AS best_score,
                           (SELECT array_agg(DISTINCT route_source ORDER BY route_source)
                            FROM agent_goal_ranked_candidate source_candidate
                            CROSS JOIN LATERAL unnest(source_candidate.retrieval_sources) route_source
                            WHERE source_candidate.run_id = candidate.run_id
                              AND source_candidate.goal_id = candidate.goal_id
                              AND source_candidate.chunk_id = candidate.chunk_id) AS retrieval_sources
                    FROM phase_candidates candidate
                    GROUP BY candidate.run_id, candidate.goal_id, candidate.chunk_id,
                             candidate.document_id, candidate.document_version_id
                ), goal_chunk_ranks AS (
                    SELECT candidate.*,
                           row_number() OVER (
                               PARTITION BY candidate.goal_id
                               ORDER BY candidate.fusion_score DESC, candidate.best_phase_rank,
                                        candidate.best_score DESC, candidate.chunk_id
                           ) AS goal_chunk_rank
                    FROM goal_fused_chunks candidate
                ), goal_documents AS (
                    SELECT DISTINCT ON (candidate.goal_id, candidate.document_id)
                           candidate.*
                    FROM goal_chunk_ranks candidate
                    ORDER BY candidate.goal_id, candidate.document_id, candidate.goal_chunk_rank
                ), goal_document_ranks AS (
                    SELECT candidate.*,
                           row_number() OVER (
                               PARTITION BY candidate.goal_id
                               ORDER BY candidate.goal_chunk_rank, candidate.document_id
                           ) AS goal_document_rank
                    FROM goal_documents candidate
                ), balanced_documents AS (
                    SELECT candidate.*,
                           row_number() OVER (
                               PARTITION BY candidate.document_id
                               ORDER BY candidate.goal_document_rank, candidate.goal_order,
                                        candidate.goal_id, candidate.chunk_id
                           ) AS document_occurrence
                    FROM goal_document_ranks candidate
                )
                SELECT chunk.id AS chunk_id, chunk.parent_chunk_id, document.id AS document_id,
                       version.id AS document_version_id, document.title AS document_title, chunk.chunk_text,
                       candidate.fusion_score AS score,
                       COALESCE(candidate.retrieval_sources, ARRAY[]::text[]) AS retrieval_sources,
                       (SELECT min(block.page_number)
                        FROM document_block block WHERE block.id = ANY(chunk.source_block_ids)) AS page_number,
                       NULL::integer AS source_start, NULL::integer AS source_end
                FROM balanced_documents candidate
                JOIN chunk ON chunk.id = candidate.chunk_id
                JOIN document_version version ON version.id = candidate.document_version_id
                    AND version.id = chunk.document_version_id
                JOIN document ON document.id = candidate.document_id AND document.id = version.document_id
                WHERE candidate.document_occurrence = 1
                ORDER BY candidate.goal_document_rank, candidate.goal_order,
                         candidate.goal_id, candidate.chunk_id
                LIMIT 40
                """, ragRunId, organizationId).map(record -> new RetrievalHit(
                record.get("chunk_id", UUID.class), record.get("parent_chunk_id", UUID.class),
                record.get("document_id", UUID.class), record.get("document_version_id", UUID.class),
                record.get("document_title", String.class), record.get("chunk_text", String.class),
                record.get("score", Double.class),
                Arrays.asList(record.get("retrieval_sources", String[].class)),
                record.get("page_number", Integer.class), record.get("source_start", Integer.class),
                record.get("source_end", Integer.class)
        ));
    }

    /**
     * v7 keeps retrieval rank spaces separate and projects accepted evidence
     * into the final document ranking only after each Goal has been balanced.
     */
    private List<RetrievalHit> findAgentV7Candidates(UUID organizationId, UUID ragRunId) {
        return dsl.fetch("""
                WITH evidence_by_document AS (
                    SELECT e.run_id, e.sub_question_id AS goal_id, e.document_id,
                           coalesce(bool_or(er.status = 'ACTIVE'), false) AS has_active_evidence,
                           count(DISTINCT er.requirement_id) FILTER (WHERE er.status = 'ACTIVE')
                               AS covered_requirements
                    FROM evidence_item e
                    LEFT JOIN evidence_requirement er ON er.evidence_id = e.id
                    WHERE e.run_id = ? AND e.deep_read = true
                    GROUP BY e.run_id, e.sub_question_id, e.document_id
                ), phase_candidates AS (
                    SELECT candidate.run_id, candidate.goal_id, candidate.goal_order, candidate.phase,
                           candidate.chunk_id, candidate.document_id, candidate.document_version_id,
                           candidate.rerank_rank, candidate.rerank_score,
                           candidate.rrf_rank, candidate.rrf_score,
                           run.pipeline_version,
                           COALESCE(NULLIF(run.runtime_snapshot #>> '{retrieval,rerankOutputLimit}', '')
                               ::double precision, 12.0) AS rerank_limit,
                           COALESCE(NULLIF(run.runtime_snapshot #>> '{retrieval,rrfCandidateLimit}', '')
                               ::double precision, 60.0) AS rrf_limit
                    FROM agent_goal_ranked_candidate candidate
                    JOIN rag_run run ON run.id = candidate.run_id
                    WHERE candidate.run_id = ? AND run.organization_id = ?
                      AND (candidate.rerank_rank IS NOT NULL OR candidate.rrf_rank IS NOT NULL)
                ), goal_fused_chunks AS (
                    SELECT candidate.run_id, candidate.goal_id, min(candidate.goal_order) AS goal_order,
                           candidate.chunk_id, candidate.document_id, candidate.document_version_id,
                           sum(1.0 / (60.0 + candidate.rrf_rank))
                               FILTER (WHERE candidate.rrf_rank IS NOT NULL) AS fusion_score,
                           bool_or(candidate.rerank_rank IS NOT NULL) AS has_rerank_result,
                           min(candidate.rerank_rank) AS best_rerank_rank,
                           max(candidate.rerank_score) AS best_rerank_score,
                           min(candidate.rrf_rank) AS best_rrf_rank,
                           max(candidate.rrf_score) AS best_rrf_score,
                           max(candidate.rerank_limit) AS rerank_limit,
                           min(least(
                               coalesce(candidate.rerank_rank / nullif(candidate.rerank_limit, 0), 999.0),
                               coalesce(candidate.rrf_rank / nullif(candidate.rrf_limit, 0), 999.0)
                           )) AS best_normalized_retrieval_rank,
                           coalesce(bool_or(evidence.has_active_evidence), false) AS has_active_evidence,
                           coalesce(max(evidence.covered_requirements), 0) AS covered_requirements,
                           max(candidate.pipeline_version) AS pipeline_version,
                           (SELECT array_agg(DISTINCT route_source ORDER BY route_source)
                            FROM agent_goal_ranked_candidate source_candidate
                            CROSS JOIN LATERAL unnest(source_candidate.retrieval_sources) route_source
                            WHERE source_candidate.run_id = candidate.run_id
                              AND source_candidate.goal_id = candidate.goal_id
                              AND source_candidate.chunk_id = candidate.chunk_id) AS retrieval_sources
                    FROM phase_candidates candidate
                    LEFT JOIN evidence_by_document evidence
                      ON evidence.run_id = candidate.run_id
                     AND evidence.goal_id = candidate.goal_id
                     AND evidence.document_id = candidate.document_id
                    GROUP BY candidate.run_id, candidate.goal_id, candidate.chunk_id,
                             candidate.document_id, candidate.document_version_id
                ), goal_chunk_ranks AS (
                    SELECT candidate.*,
                           row_number() OVER (
                               PARTITION BY candidate.run_id, candidate.goal_id
                               ORDER BY
                                        CASE
                                            WHEN candidate.pipeline_version IN ('agentic-rag-v8', 'deep-rag-final')
                                                THEN CASE
                                                    WHEN candidate.has_active_evidence
                                                         OR candidate.best_rerank_rank <= candidate.rerank_limit THEN 0
                                                    ELSE 1
                                                END
                                            ELSE CASE WHEN candidate.has_active_evidence THEN 0 ELSE 1 END
                                        END,
                                        candidate.has_active_evidence DESC,
                                        candidate.best_normalized_retrieval_rank,
                                        candidate.fusion_score DESC NULLS LAST,
                                        candidate.best_rerank_score DESC NULLS LAST,
                                        candidate.best_rrf_score DESC NULLS LAST,
                                        candidate.covered_requirements DESC,
                                        candidate.chunk_id
                           ) AS goal_chunk_rank
                    FROM goal_fused_chunks candidate
                ), goal_documents AS (
                    SELECT DISTINCT ON (candidate.goal_id, candidate.document_id)
                           candidate.*
                    FROM goal_chunk_ranks candidate
                    ORDER BY candidate.goal_id, candidate.document_id, candidate.goal_chunk_rank
                ), goal_document_ranks AS (
                    SELECT candidate.*,
                           row_number() OVER (
                               PARTITION BY candidate.run_id, candidate.goal_id
                               ORDER BY candidate.goal_chunk_rank, candidate.document_id
                           ) AS goal_document_rank
                    FROM goal_documents candidate
                ), balanced_documents AS (
                    SELECT candidate.*,
                           row_number() OVER (
                               PARTITION BY candidate.run_id, candidate.document_id
                               ORDER BY candidate.goal_document_rank, candidate.goal_order,
                                        candidate.goal_id, candidate.chunk_id
                           ) AS document_occurrence
                    FROM goal_document_ranks candidate
                ), unique_documents AS (
                    SELECT candidate.*
                    FROM balanced_documents candidate
                    WHERE candidate.document_occurrence = 1
                ), rebalanced_documents AS (
                    SELECT candidate.*,
                           row_number() OVER (
                               PARTITION BY candidate.run_id, candidate.goal_id
                               ORDER BY candidate.goal_document_rank, candidate.document_id
                           ) AS balanced_goal_rank
                    FROM unique_documents candidate
                )
                SELECT chunk.id AS chunk_id, chunk.parent_chunk_id, document.id AS document_id,
                       version.id AS document_version_id, document.title AS document_title, chunk.chunk_text,
                       COALESCE(candidate.best_rerank_score, candidate.fusion_score,
                                candidate.best_rrf_score, 0) AS score,
                       COALESCE(candidate.retrieval_sources, ARRAY[]::text[]) AS retrieval_sources,
                       (SELECT min(block.page_number)
                        FROM document_block block WHERE block.id = ANY(chunk.source_block_ids)) AS page_number,
                       NULL::integer AS source_start, NULL::integer AS source_end
                FROM rebalanced_documents candidate
                JOIN chunk ON chunk.id = candidate.chunk_id
                JOIN document_version version ON version.id = candidate.document_version_id
                    AND version.id = chunk.document_version_id
                JOIN document ON document.id = candidate.document_id AND document.id = version.document_id
                ORDER BY candidate.balanced_goal_rank, candidate.goal_order,
                         candidate.goal_id, candidate.chunk_id
                LIMIT 40
                """, ragRunId, ragRunId, organizationId).map(record -> new RetrievalHit(
                record.get("chunk_id", UUID.class), record.get("parent_chunk_id", UUID.class),
                record.get("document_id", UUID.class), record.get("document_version_id", UUID.class),
                record.get("document_title", String.class), record.get("chunk_text", String.class),
                record.get("score", Double.class),
                Arrays.asList(record.get("retrieval_sources", String[].class)),
                record.get("page_number", Integer.class), record.get("source_start", Integer.class),
                record.get("source_end", Integer.class)
        ));
    }

    @Override
    public List<String> findRagRunAcceptedEvidenceTexts(UUID organizationId, UUID ragRunId) {
        return dsl.fetch("""
                WITH eligible AS (
                    SELECT evidence.quote_text, evidence.sub_question_id,
                           evidence.first_accepted_phase, evidence.created_at, evidence.id,
                           row_number() OVER (
                               PARTITION BY evidence.quote_text
                               ORDER BY evidence.sub_question_id NULLS LAST,
                                        CASE evidence.first_accepted_phase
                                            WHEN 'PRIMARY' THEN 1
                                            WHEN 'REPAIR' THEN 2
                                            ELSE 3
                                        END,
                                        evidence.created_at, evidence.id
                           ) AS quote_occurrence
                    FROM evidence_item evidence
                    JOIN rag_run run ON run.id = evidence.run_id
                    JOIN document ON document.id = evidence.document_id
                    JOIN document_version version ON version.id = evidence.document_version_id
                    WHERE evidence.run_id = ? AND run.organization_id = ?
                      AND evidence.deep_read = true
                      AND btrim(evidence.quote_text) <> ''
                      AND document.organization_id = run.organization_id
                      AND document.status = 'ACTIVE'
                      AND document.current_version_id = version.id
                      AND version.status = 'PUBLISHED'
                      AND EXISTS (
                          SELECT 1
                          FROM evidence_requirement requirement
                          WHERE requirement.evidence_id = evidence.id
                            AND requirement.status = 'ACTIVE'
                      )
                )
                SELECT quote_text
                FROM eligible
                WHERE quote_occurrence = 1
                ORDER BY sub_question_id NULLS LAST,
                         CASE first_accepted_phase
                             WHEN 'PRIMARY' THEN 1
                             WHEN 'REPAIR' THEN 2
                             ELSE 3
                         END,
                         created_at, id
                """, ragRunId, organizationId)
                .map(record -> record.get("quote_text", String.class));
    }

    @Override
    public Map<String, Object> findRagRunRetrievalDiagnostics(UUID organizationId, UUID ragRunId) {
        var pipelineVersion = dsl.fetchOptional("""
                SELECT pipeline_version FROM rag_run WHERE id = ? AND organization_id = ?
                """, ragRunId, organizationId)
                .map(record -> record.get("pipeline_version", String.class)).orElse("");
        if ("agentic-rag-v7".equals(pipelineVersion)) {
            return findAgentV7Diagnostics(organizationId, ragRunId);
        }
        if ("agentic-rag-v8".equals(pipelineVersion)) {
            var diagnostics = new LinkedHashMap<>(findAgentV7Diagnostics(organizationId, ragRunId));
            diagnostics.put("pipelineVersion", pipelineVersion);
            return diagnostics;
        }
        if ("deep-rag-final".equals(pipelineVersion)) {
            return findDeepFinalDiagnostics(organizationId, ragRunId);
        }
        if ("agentic-rag-v5".equals(pipelineVersion)) {
            return findAgentV5Diagnostics(organizationId, ragRunId);
        }
        if ("agentic-rag-v4".equals(pipelineVersion)) {
            return findAgentV4Diagnostics(organizationId, ragRunId);
        }
        if ("agentic-hybrid-v2".equals(pipelineVersion)) {
            return findAgentHybridDiagnostics(organizationId, ragRunId);
        }
        var result = new LinkedHashMap<String, Object>();
        dsl.fetch("""
                SELECT tc.tool_name, count(*) AS calls,
                       count(*) FILTER (WHERE tc.status = 'SUCCEEDED') AS succeeded,
                       count(*) FILTER (WHERE tc.status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE tc.status = 'FAILED'
                           AND coalesce(tc.error ->> 'message', '') ILIKE '%budget%') AS budget_rejected,
                       coalesce(sum(tc.result_count) FILTER (WHERE tc.status = 'SUCCEEDED'), 0) AS result_count
                FROM agent_tool_call tc
                JOIN rag_run rr ON rr.id = tc.run_id
                WHERE tc.run_id = ? AND rr.organization_id = ?
                GROUP BY tc.tool_name ORDER BY tc.tool_name
                """, ragRunId, organizationId).forEach(record -> result.put(
                "tool." + record.get("tool_name", String.class), Map.of(
                        "calls", count(record, "calls"), "succeeded", count(record, "succeeded"),
                        "failed", count(record, "failed"), "budgetRejected", count(record, "budget_rejected"),
                        "resultCount", count(record, "result_count"))));
        dsl.fetchOptional("""
                SELECT count(DISTINCT step.id) AS iterations,
                       coalesce(sum((step.token_usage ->> 'inputTokens')::bigint), 0) AS input_tokens,
                       coalesce(sum((step.token_usage ->> 'outputTokens')::bigint), 0) AS output_tokens,
                       coalesce(sum((step.token_usage ->> 'totalTokens')::bigint), 0) AS total_tokens,
                       (SELECT count(*) FROM rag_run_event event
                        WHERE event.run_id = ? AND event.event_type = 'CONTEXT_COMPRESSED') AS context_compressions
                FROM agent_react_step step
                JOIN rag_run run ON run.id = step.run_id
                WHERE step.run_id = ? AND run.organization_id = ?
                """, ragRunId, ragRunId, organizationId).ifPresent(record -> {
            result.put("iterationCount", count(record, "iterations"));
            result.put("inputTokens", count(record, "input_tokens"));
            result.put("outputTokens", count(record, "output_tokens"));
            result.put("totalTokens", count(record, "total_tokens"));
            result.put("contextCompressionCount", count(record, "context_compressions"));
        });
        dsl.fetchOptional("""
                SELECT count(*) AS ref_count,
                       count(DISTINCT reference.document_id) AS documents,
                       count(*) FILTER (WHERE reference.deep_read) AS deep_read_references,
                       count(DISTINCT reference.document_id) FILTER (WHERE reference.deep_read) AS deep_read_documents,
                       count(*) FILTER (WHERE document.organization_id <> run.organization_id
                           OR document.status <> 'ACTIVE'
                           OR document.current_version_id <> reference.document_version_id
                           OR document_version.status <> 'PUBLISHED') AS scope_leaks,
                       coalesce((SELECT array_agg(ids.document_id ORDER BY ids.first_deep_read_order NULLS LAST,
                                                        ids.first_discovery_order, ids.document_id)
                                 FROM (SELECT document_id, min(first_deep_read_order) AS first_deep_read_order,
                                              min(first_discovery_order) AS first_discovery_order
                                       FROM agent_knowledge_reference
                                       WHERE run_id = ? AND deep_read
                                       GROUP BY document_id) ids), ARRAY[]::uuid[]) AS deep_read_document_ids,
                       coalesce((SELECT array_agg(ids.document_id ORDER BY ids.first_discovery_order, ids.document_id)
                                 FROM (SELECT document_id, min(first_discovery_order) AS first_discovery_order
                                       FROM agent_knowledge_reference
                                       WHERE run_id = ?
                                       GROUP BY document_id) ids), ARRAY[]::uuid[]) AS all_document_ids
                FROM agent_knowledge_reference reference
                JOIN rag_run run ON run.id = reference.run_id
                JOIN document ON document.id = reference.document_id
                JOIN document_version ON document_version.id = reference.document_version_id
                WHERE reference.run_id = ? AND run.organization_id = ?
                """, ragRunId, ragRunId, ragRunId, organizationId).ifPresent(record -> {
            result.put("referenceCount", count(record, "ref_count"));
            result.put("documentCount", count(record, "documents"));
            result.put("deepReadReferenceCount", count(record, "deep_read_references"));
            result.put("deepReadDocumentCount", count(record, "deep_read_documents"));
            result.put("scopeLeakCount", count(record, "scope_leaks"));
            result.put("deepReadDocumentIds", Arrays.asList(record.get("deep_read_document_ids", UUID[].class)));
            result.put("allDocumentIds", Arrays.asList(record.get("all_document_ids", UUID[].class)));
        });
        addRouteDiagnostics(result, organizationId, ragRunId);
        return Map.copyOf(result);
    }

    private Map<String, Object> findAgentV4Diagnostics(UUID organizationId, UUID ragRunId) {
        return findVersionedAgentDiagnostics(organizationId, ragRunId, "agentic-v4", 3);
    }

    private Map<String, Object> findVersionedAgentDiagnostics(
            UUID organizationId,
            UUID ragRunId,
            String operationPrefix,
            int checkpointVersion
    ) {
        var result = new LinkedHashMap<String, Object>();
        dsl.fetchOptional("""
                SELECT count(*) AS task_count,
                       count(*) FILTER (WHERE task.status = 'SUCCEEDED') AS succeeded,
                       count(*) FILTER (WHERE task.status IN ('FAILED', 'CANCELLED')) AS failed,
                       count(*) FILTER (WHERE task.search_mode = 'KEYWORD') AS keyword_calls,
                       count(*) FILTER (WHERE task.search_mode = 'SEMANTIC') AS semantic_calls,
                       coalesce(sum(task.result_count) FILTER (WHERE task.status = 'SUCCEEDED'), 0) AS result_count,
                       count(*) FILTER (WHERE task.research_phase = 'REPAIR') AS repair_queries
                FROM agent_retrieval_task task
                JOIN rag_run run ON run.id = task.run_id
                WHERE task.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("retrievalTaskCount", count(record, "task_count"));
            result.put("physicalSearchCount", count(record, "task_count"));
            result.put("keywordSearchCount", count(record, "keyword_calls"));
            result.put("semanticSearchCount", count(record, "semantic_calls"));
            result.put("repairQueryCount", count(record, "repair_queries"));
            result.put("tool.search", toolDiagnostic(count(record, "task_count"),
                    count(record, "succeeded"), count(record, "failed"), count(record, "result_count")));
        });
        dsl.fetchOptional("""
                SELECT count(*) AS logical_calls,
                       count(*) FILTER (WHERE call.status = 'FAILED') AS failed_logical_calls,
                       coalesce(sum(call.input_tokens), 0) AS input_tokens,
                       coalesce(sum(call.output_tokens), 0) AS output_tokens,
                       coalesce(sum(call.latency_ms), 0) AS model_latency_ms,
                       count(*) FILTER (WHERE call.operation = ?
                           OR call.operation LIKE 'agentic-v8-request-analysis%') AS planner_calls,
                       count(*) FILTER (WHERE call.operation = ?
                           OR call.operation LIKE 'agentic-v8-%deep-read%'
                           OR call.operation LIKE 'agentic-v8-%parent-%') AS deep_read_calls,
                       count(*) FILTER (WHERE call.operation = ?
                           OR call.operation LIKE 'agentic-v8-%evidence-judge%') AS judge_calls,
                       count(*) FILTER (WHERE call.operation = ?
                           OR call.operation LIKE 'agentic-v8-%final-answer%') AS final_answer_calls,
                       count(*) FILTER (WHERE call.repair_used) AS repair_attempted
                FROM agent_model_logical_call call
                JOIN rag_run run ON run.id = call.run_id
                WHERE call.run_id = ? AND run.organization_id = ?
                """, operationPrefix + "-request-analysis", operationPrefix + "-deep-read",
                operationPrefix + "-evidence-judge", operationPrefix + "-final-answer",
                ragRunId, organizationId).ifPresent(record -> {
            result.put("modelLogicalCallCount", count(record, "logical_calls"));
            result.put("modelFailedLogicalCallCount", count(record, "failed_logical_calls"));
            result.put("plannerCallCount", count(record, "planner_calls"));
            result.put("deepReadCallCount", count(record, "deep_read_calls"));
            result.put("judgeCallCount", count(record, "judge_calls"));
            result.put("finalAnswerCallCount", count(record, "final_answer_calls"));
            result.put("modelRepairCount", count(record, "repair_attempted"));
            result.put("inputTokens", count(record, "input_tokens"));
            result.put("outputTokens", count(record, "output_tokens"));
            result.put("totalTokens", count(record, "input_tokens") + count(record, "output_tokens"));
            result.put("modelLatencyMs", count(record, "model_latency_ms"));
        });
        dsl.fetchOptional("""
                SELECT count(*) AS attempt_count,
                       count(*) FILTER (WHERE attempt.status = 'FAILED') AS failed_attempts,
                       count(*) FILTER (WHERE attempt.token_usage_estimated) AS estimated_attempts
                FROM agent_model_attempt attempt
                JOIN agent_model_logical_call call ON call.id = attempt.logical_call_id
                JOIN rag_run run ON run.id = call.run_id
                WHERE call.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("modelPhysicalAttemptCount", count(record, "attempt_count"));
            result.put("modelFailedAttemptCount", count(record, "failed_attempts"));
            result.put("modelEstimatedTokenAttemptCount", count(record, "estimated_attempts"));
        });
        dsl.fetchOptional("""
                SELECT count(*) FILTER (WHERE action.operation = 'RERANK') AS rerank_calls,
                       count(*) FILTER (WHERE action.operation = 'PARENT_READ') AS parent_read_calls,
                       count(*) FILTER (WHERE action.operation IN ('RERANK', 'PARENT_READ')
                           AND action.status = 'FAILED') AS failed_support_actions
                FROM agent_external_action action
                JOIN rag_run run ON run.id = action.run_id
                WHERE action.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("rerankCallCount", count(record, "rerank_calls"));
            result.put("parentReadCallCount", count(record, "parent_read_calls"));
            result.put("failedSupportActionCount", count(record, "failed_support_actions"));
        });
        dsl.fetchOptional("""
                SELECT count(*) AS evidence_count,
                       count(DISTINCT evidence.document_id) AS document_count,
                       count(DISTINCT relation.requirement_id) AS linked_requirement_count
                FROM evidence_item evidence
                LEFT JOIN evidence_requirement relation ON relation.evidence_id = evidence.id
                    AND relation.status = 'ACTIVE'
                JOIN rag_run run ON run.id = evidence.run_id
                WHERE evidence.run_id = ? AND run.organization_id = ? AND evidence.span_id IS NOT NULL
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("acceptedEvidenceCount", count(record, "evidence_count"));
            result.put("documentCount", count(record, "document_count"));
            result.put("evidenceLinkedRequirementCount", count(record, "linked_requirement_count"));
        });
        dsl.fetchOptional("""
                SELECT count(*) FILTER (WHERE value = 'COVERED') AS covered,
                       count(*) FILTER (WHERE value = 'MISSING') AS missing,
                       count(*) FILTER (WHERE value = 'CONFLICTING') AS conflicting,
                       count(*) FILTER (WHERE value = 'NOT_FOUND_WITHIN_BUDGET') AS not_found
                FROM agent_run_checkpoint checkpoint,
                     LATERAL jsonb_each_text(COALESCE(
                         checkpoint.state -> 'coverage' -> 'requirementStatuses', '{}'::jsonb)) statuses(key, value)
                WHERE checkpoint.run_id = ? AND checkpoint.checkpoint_version = ?
                """, ragRunId, checkpointVersion).ifPresent(record -> {
            result.put("coveredRequirementCount", count(record, "covered"));
            result.put("missingRequirementCount", count(record, "missing"));
            result.put("conflictingRequirementCount", count(record, "conflicting"));
            result.put("notFoundRequirementCount", count(record, "not_found"));
        });
        dsl.fetchOptional("""
                SELECT count(*) AS outcome_count,
                       count(*) FILTER (WHERE outcome.phase = 'PRIMARY') AS primary_outcomes,
                       count(*) FILTER (WHERE outcome.phase = 'REPAIR') AS repair_outcomes,
                       count(*) FILTER (WHERE outcome.may_have_hidden_evidence) AS hidden_evidence_outcomes
                FROM agent_goal_research_outcome outcome
                JOIN rag_run run ON run.id = outcome.run_id
                WHERE outcome.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("goalResearchOutcomeCount", count(record, "outcome_count"));
            result.put("primaryGoalCount", count(record, "primary_outcomes"));
            result.put("repairGoalCount", count(record, "repair_outcomes"));
            result.put("hiddenEvidenceOutcomeCount", count(record, "hidden_evidence_outcomes"));
        });
        dsl.fetchOptional("""
                SELECT answer_mode, stop_reason,
                       EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000 AS latency_ms
                FROM rag_run WHERE id = ? AND organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("answerMode", java.util.Objects.toString(record.get("answer_mode"), ""));
            result.put("stopReason", java.util.Objects.toString(record.get("stop_reason"), ""));
            var latency = record.get("latency_ms", java.math.BigDecimal.class);
            result.put("latencyMs", latency == null ? 0L : latency.longValue());
        });
        addRouteDiagnostics(result, organizationId, ragRunId);
        return Map.copyOf(result);
    }

    private Map<String, Object> findAgentV5Diagnostics(UUID organizationId, UUID ragRunId) {
        var result = new LinkedHashMap<>(findVersionedAgentDiagnostics(
                organizationId, ragRunId, "agentic-v5", 4));
        addGoalRankedCandidateDiagnostics(result, organizationId, ragRunId);
        result.put("retrievalRankingSource", "agent_goal_ranked_candidate");
        result.put("retrievalProjection", "GOAL_PHASE_RRF_BALANCED");
        return Map.copyOf(result);
    }

    private Map<String, Object> findDeepFinalDiagnostics(UUID organizationId, UUID ragRunId) {
        var result = new LinkedHashMap<>(findVersionedAgentDiagnostics(
                organizationId, ragRunId, "deep", 3));
        dsl.fetchOptional("""
                SELECT count(*) FILTER (WHERE call.operation LIKE 'deep-request-analysis%') AS planner_calls,
                       count(*) FILTER (WHERE call.operation LIKE 'deep-%read%') AS deep_read_calls,
                       count(*) FILTER (WHERE call.operation LIKE 'deep-evidence-judge%') AS judge_calls,
                       count(*) FILTER (WHERE call.operation LIKE 'deep-final-answer%') AS final_answer_calls
                FROM agent_model_logical_call call
                JOIN rag_run run ON run.id = call.run_id
                WHERE call.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("plannerCallCount", count(record, "planner_calls"));
            result.put("deepReadCallCount", count(record, "deep_read_calls"));
            result.put("judgeCallCount", count(record, "judge_calls"));
            result.put("finalAnswerCallCount", count(record, "final_answer_calls"));
        });
        addGoalRankedCandidateDiagnostics(result, organizationId, ragRunId);
        result.put("retrievalRankingSource", "agent_goal_ranked_candidate");
        result.put("retrievalProjection", "EVIDENCE_BOOLEAN_GOAL_BALANCED_V2");
        result.put("rankingPolicy", "EVIDENCE_BOOLEAN_GOAL_BALANCED_V2");
        result.put("rankingSchemaVersion", 3);
        result.put("pipelineVersion", "deep-rag-final");
        return Map.copyOf(result);
    }

    private void addGoalRankedCandidateDiagnostics(
            Map<String, Object> result,
            UUID organizationId,
            UUID ragRunId
    ) {
        dsl.fetchOptional("""
                SELECT count(*) AS ranked_candidates,
                       count(DISTINCT candidate.goal_id) AS ranked_goals,
                       count(*) FILTER (WHERE candidate.phase = 'PRIMARY') AS primary_candidates,
                       count(*) FILTER (WHERE candidate.phase = 'REPAIR') AS repair_candidates,
                       count(*) FILTER (WHERE candidate.rerank_fallback) AS rerank_fallback_candidates,
                       count(*) FILTER (WHERE candidate.selected_for_parent) AS selected_for_parent
                FROM agent_goal_ranked_candidate candidate
                JOIN rag_run run ON run.id = candidate.run_id
                WHERE candidate.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("postRerankCandidateCount", count(record, "ranked_candidates"));
            result.put("postRerankGoalCount", count(record, "ranked_goals"));
            result.put("primaryPostRerankCandidateCount", count(record, "primary_candidates"));
            result.put("repairPostRerankCandidateCount", count(record, "repair_candidates"));
            result.put("rerankFallbackCandidateCount", count(record, "rerank_fallback_candidates"));
            result.put("selectedForParentCandidateCount", count(record, "selected_for_parent"));
        });
    }

    private Map<String, Object> findAgentHybridDiagnostics(UUID organizationId, UUID ragRunId) {
        var result = new LinkedHashMap<String, Object>();
        dsl.fetchOptional("""
                SELECT count(*) AS task_count,
                       count(*) FILTER (WHERE task.status = 'SUCCEEDED') AS succeeded,
                       count(*) FILTER (WHERE task.status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE task.search_mode IN ('KEYWORD', 'HYBRID')
                           AND task.status <> 'PENDING') AS keyword_calls,
                       count(*) FILTER (WHERE task.search_mode IN ('SEMANTIC', 'HYBRID')
                           AND task.status <> 'PENDING') AS semantic_calls,
                       count(*) FILTER (WHERE task.status = 'SUCCEEDED'
                           AND task.search_mode IN ('KEYWORD', 'HYBRID')) AS keyword_succeeded,
                       count(*) FILTER (WHERE task.status = 'SUCCEEDED'
                           AND task.search_mode IN ('SEMANTIC', 'HYBRID')) AS semantic_succeeded,
                       count(*) FILTER (WHERE task.status IN ('FAILED', 'CANCELLED')
                           AND task.search_mode IN ('KEYWORD', 'HYBRID')) AS keyword_failed,
                       count(*) FILTER (WHERE task.status IN ('FAILED', 'CANCELLED')
                           AND task.search_mode IN ('SEMANTIC', 'HYBRID')) AS semantic_failed,
                       coalesce(sum(task.result_count) FILTER (WHERE task.status = 'SUCCEEDED'
                           AND task.search_mode IN ('KEYWORD', 'HYBRID')), 0) AS keyword_result_count,
                       coalesce(sum(task.result_count) FILTER (WHERE task.status = 'SUCCEEDED'
                           AND task.search_mode IN ('SEMANTIC', 'HYBRID')), 0) AS semantic_result_count
                FROM agent_retrieval_task task
                JOIN rag_run run ON run.id = task.run_id
                WHERE task.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            long taskCount = count(record, "task_count");
            long keywordCalls = count(record, "keyword_calls");
            long semanticCalls = count(record, "semantic_calls");
            long keywordSucceeded = count(record, "keyword_succeeded");
            long semanticSucceeded = count(record, "semantic_succeeded");
            long keywordFailed = count(record, "keyword_failed");
            long semanticFailed = count(record, "semantic_failed");
            result.put("retrievalTaskCount", taskCount);
            result.put("tool.keyword_search", toolDiagnostic(
                    keywordCalls, keywordSucceeded, keywordFailed,
                    count(record, "keyword_result_count")));
            result.put("tool.semantic_search", toolDiagnostic(
                    semanticCalls, semanticSucceeded, semanticFailed,
                    count(record, "semantic_result_count")));
        });
        dsl.fetchOptional("""
                SELECT count(*) FILTER (WHERE event.event_type = 'RERANK_COMPLETED') AS rerank_completed,
                       count(*) FILTER (WHERE event.event_type = 'RERANK_SKIPPED') AS rerank_skipped,
                       coalesce(sum(NULLIF(event.payload ->> 'resultCount', '')::bigint)
                           FILTER (WHERE event.event_type = 'RERANK_COMPLETED'), 0) AS rerank_result_count,
                       count(*) FILTER (WHERE event.event_type = 'DEEP_READ_COMPLETED') AS deep_read_completed,
                       count(*) FILTER (WHERE event.event_type = 'DEEP_READ_FAILED') AS deep_read_failed,
                       count(*) FILTER (WHERE event.event_type = 'DEEP_READ_FAILED'
                           AND event.payload ->> 'phase' = 'context-expansion') AS context_expansion_failed,
                       count(*) FILTER (WHERE event.event_type = 'EVIDENCE_JUDGE_STARTED') AS judge_started,
                       count(*) FILTER (WHERE event.event_type = 'EVIDENCE_JUDGE_COMPLETED') AS judge_completed,
                       count(*) FILTER (WHERE event.event_type = 'GAP_QUERY_CREATED') AS gap_queries
                FROM rag_run_event event
                JOIN rag_run run ON run.id = event.run_id
                WHERE event.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            long rerankCompleted = count(record, "rerank_completed");
            long rerankSkipped = count(record, "rerank_skipped");
            long judgeStarted = count(record, "judge_started");
            long judgeCompleted = count(record, "judge_completed");
            result.put("rerankSkippedCount", rerankSkipped);
            result.put("tool.rerank", toolDiagnostic(
                    rerankCompleted + rerankSkipped, rerankCompleted, rerankSkipped,
                    count(record, "rerank_result_count")));
            result.put("deepReadFailureCount", count(record, "deep_read_failed"));
            result.put("deepReadCompletedCount", count(record, "deep_read_completed"));
            result.put("contextExpansionFailureCount", count(record, "context_expansion_failed"));
            result.put("judgeCallCount", judgeStarted);
            result.put("gapQueryCount", count(record, "gap_queries"));
            result.put("tool.evidence_judge", toolDiagnostic(
                    judgeStarted, judgeCompleted, Math.max(0, judgeStarted - judgeCompleted), judgeCompleted));
        });
        dsl.fetchOptional("""
                SELECT coalesce((checkpoint.state #>> '{budget,deepReadsUsed}')::bigint, 0) AS deep_reads,
                       coalesce((checkpoint.state #>> '{budget,roundsUsed}')::bigint, 0) AS rounds
                FROM agent_run_checkpoint checkpoint
                JOIN rag_run run ON run.id = checkpoint.run_id
                WHERE checkpoint.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("deepReadPhysicalCount", count(record, "deep_reads"));
            result.put("iterationCount", count(record, "rounds"));
        });
        dsl.fetchOptional("""
                SELECT count(*) AS evidence_count,
                       count(DISTINCT evidence.document_id) AS deep_read_documents
                FROM evidence_item evidence
                JOIN rag_run run ON run.id = evidence.run_id
                WHERE evidence.run_id = ? AND run.organization_id = ? AND evidence.deep_read
                """, ragRunId, organizationId).ifPresent(record -> {
            result.put("evidenceCount", count(record, "evidence_count"));
            result.put("referenceCount", count(record, "evidence_count"));
            result.put("deepReadReferenceCount", count(record, "evidence_count"));
            result.put("deepReadDocumentCount", count(record, "deep_read_documents"));
        });
        dsl.fetchOptional("""
                SELECT count(*) AS judge_reports,
                       count(*) FILTER (WHERE sufficient) AS sufficient_reports
                FROM coverage_report report
                JOIN rag_run run ON run.id = report.run_id
                WHERE report.run_id = ? AND run.organization_id = ?
                """, ragRunId, organizationId).ifPresent(record -> {
            long reports = count(record, "judge_reports");
            result.put("judgeSufficientCount", count(record, "sufficient_reports"));
            if (!result.containsKey("judgeCallCount")) result.put("judgeCallCount", reports);
            if (number(result.get("iterationCount")) == 0) result.put("iterationCount", reports);
        });

        var allDocumentIds = dsl.fetch("""
                WITH observed AS (
                    SELECT candidate.chunk_id, candidate.created_at
                    FROM retrieval_candidate candidate WHERE candidate.run_id = ?
                    UNION ALL
                    SELECT evidence.chunk_id, evidence.created_at
                    FROM evidence_item evidence WHERE evidence.run_id = ?
                )
                SELECT document.id AS document_id
                FROM observed
                JOIN chunk ON chunk.id = observed.chunk_id
                JOIN document_version version ON version.id = chunk.document_version_id
                JOIN document ON document.id = version.document_id
                JOIN rag_run run ON run.id = ? AND run.organization_id = ?
                GROUP BY document.id
                ORDER BY min(observed.created_at), document.id
                """, ragRunId, ragRunId, ragRunId, organizationId)
                .map(record -> record.get("document_id", UUID.class));
        var deepReadDocumentIds = dsl.fetch("""
                SELECT evidence.document_id
                FROM evidence_item evidence
                JOIN rag_run run ON run.id = evidence.run_id
                WHERE evidence.run_id = ? AND run.organization_id = ? AND evidence.deep_read
                GROUP BY evidence.document_id
                ORDER BY min(evidence.created_at), evidence.document_id
                """, ragRunId, organizationId)
                .map(record -> record.get("document_id", UUID.class));
        result.put("allDocumentIds", allDocumentIds);
        result.put("deepReadDocumentIds", deepReadDocumentIds);
        result.put("documentCount", allDocumentIds.size());
        dsl.fetchOptional("""
                WITH observed_versions AS (
                    SELECT DISTINCT chunk.document_version_id
                    FROM retrieval_candidate candidate
                    JOIN chunk ON chunk.id = candidate.chunk_id
                    WHERE candidate.run_id = ?
                    UNION
                    SELECT DISTINCT evidence.document_version_id
                    FROM evidence_item evidence WHERE evidence.run_id = ?
                )
                SELECT count(*) FILTER (WHERE document.organization_id <> run.organization_id
                           OR document.status <> 'ACTIVE'
                           OR document.current_version_id <> version.id
                           OR version.status <> 'PUBLISHED') AS scope_leaks
                FROM observed_versions observed
                JOIN document_version version ON version.id = observed.document_version_id
                JOIN document ON document.id = version.document_id
                JOIN rag_run run ON run.id = ? AND run.organization_id = ?
                """, ragRunId, ragRunId, ragRunId, organizationId).ifPresent(record ->
                result.put("scopeLeakCount", count(record, "scope_leaks")));
        result.putIfAbsent("iterationCount", 0L);
        result.putIfAbsent("contextCompressionCount", 0L);
        result.putIfAbsent("inputTokens", 0L);
        result.putIfAbsent("outputTokens", 0L);
        result.putIfAbsent("totalTokens", 0L);
        result.putIfAbsent("evidenceCount", 0L);
        long deepReadPhysical = number(result.get("deepReadPhysicalCount"));
        long deepReadFailures = number(result.get("deepReadFailureCount"));
        long contextExpansionFailures = number(result.get("contextExpansionFailureCount"));
        long deepReadCalls = Math.max(deepReadPhysical + contextExpansionFailures, deepReadFailures);
        if (deepReadCalls == 0 && number(result.get("evidenceCount")) > 0) deepReadCalls = 1;
        result.put("tool.deep_read", toolDiagnostic(
                deepReadCalls, Math.max(0, deepReadCalls - deepReadFailures), deepReadFailures,
                number(result.get("evidenceCount"))));
        result.putIfAbsent("tool.rerank", toolDiagnostic(0, 0, 0, 0));
        result.putIfAbsent("tool.evidence_judge", toolDiagnostic(0, 0, 0, 0));
        addRouteDiagnostics(result, organizationId, ragRunId);
        return Map.copyOf(result);
    }

    private Map<String, Object> toolDiagnostic(long calls, long succeeded, long failed, long resultCount) {
        return Map.of(
                "calls", Math.max(0, calls),
                "succeeded", Math.max(0, succeeded),
                "failed", Math.max(0, failed),
                "budgetRejected", 0L,
                "resultCount", Math.max(0, resultCount));
    }

    private void addRouteDiagnostics(
            Map<String, Object> result,
            UUID organizationId,
            UUID ragRunId
    ) {
        dsl.fetchOptional("""
                SELECT route.payload::text AS payload,
                       CASE WHEN run.started_at IS NULL THEN NULL
                            ELSE (extract(epoch FROM (route.created_at - run.started_at)) * 1000)::bigint
                       END AS route_latency_ms,
                       EXISTS (
                           SELECT 1 FROM rag_run_event classified
                           WHERE classified.run_id = route.run_id
                             AND classified.event_type = 'INTENT_CLASSIFIED'
                       ) AS classified
                FROM rag_run_event route
                JOIN rag_run run ON run.id = route.run_id AND run.organization_id = ?
                WHERE route.run_id = ? AND route.event_type = 'ROUTE_SELECTED'
                ORDER BY route.sequence DESC LIMIT 1
                """, organizationId, ragRunId).ifPresent(record -> {
            var payload = map(record.get("payload", String.class));
            var reason = String.valueOf(payload.getOrDefault("reason", "unknown"));
            var source = "user-override".equals(reason) ? "USER_OVERRIDE"
                    : "router-fallback-deep".equals(reason) ? "FALLBACK"
                    : Boolean.TRUE.equals(record.get("classified", Boolean.class)) ? "LLM" : "HEURISTIC";
            result.put("routeReason", reason);
            result.put("routeDecisionSource", source);
            result.put("routeClassifiedByModel", Boolean.TRUE.equals(record.get("classified", Boolean.class)));
            result.put("routerFallback", "FALLBACK".equals(source));
            var latency = record.get("route_latency_ms", Long.class);
            if (latency != null) result.put("routeLatencyMs", Math.max(0, latency));
        });
    }

    private Map<String, Object> findAgentV7Diagnostics(UUID organizationId, UUID ragRunId) {
        var diagnostics = new LinkedHashMap<>(findAgentV5Diagnostics(organizationId, ragRunId));
        diagnostics.put("retrievalProjection", "EVIDENCE_BOOLEAN_GOAL_BALANCED_V2");
        diagnostics.put("rankingPolicy", "EVIDENCE_BOOLEAN_GOAL_BALANCED_V2");
        diagnostics.put("rankingSchemaVersion", 3);
        diagnostics.put("pipelineVersion", "agentic-rag-v7");
        return diagnostics;
    }

    @Override
    public List<CitationEvidence> findRagRunCitations(UUID organizationId, UUID ragRunId) {
        return dsl.fetch("""
                SELECT citation.citation_index, citation.document_id, citation.document_version_id,
                       citation.chunk_id, citation.quote_text
                FROM citation
                JOIN rag_run run ON run.id = citation.run_id
                WHERE citation.run_id = ? AND run.organization_id = ?
                ORDER BY citation.citation_index
                """, ragRunId, organizationId).map(record -> new CitationEvidence(
                record.get("citation_index", Integer.class), record.get("document_id", UUID.class),
                record.get("document_version_id", UUID.class), record.get("chunk_id", UUID.class),
                record.get("quote_text", String.class)
        ));
    }

    @Override
    public List<EvaluationResult> findResults(UUID organizationId, UUID runId) {
        return dsl.fetch("""
                SELECT result.id, result.evaluation_run_id, result.evaluation_case_id, result.rag_run_id,
                       result.metrics::text AS metrics, result.error_message, result.created_at
                FROM evaluation_result result
                JOIN evaluation_run run ON run.id = result.evaluation_run_id
                JOIN evaluation_dataset dataset ON dataset.id = run.dataset_id
                WHERE dataset.organization_id = ? AND result.evaluation_run_id = ?
                ORDER BY result.created_at, result.evaluation_case_id
                """, organizationId, runId).map(record -> new EvaluationResult(
                record.get("id", UUID.class),
                record.get("evaluation_run_id", UUID.class),
                record.get("evaluation_case_id", UUID.class),
                record.get("rag_run_id", UUID.class),
                map(record.get("metrics", String.class)),
                record.get("error_message", String.class),
                instant(record, "created_at")
        ));
    }

    private EvaluationDataset dataset(Record record) {
        return new EvaluationDataset(
                record.get("id", UUID.class),
                record.get("organization_id", UUID.class),
                record.get("name", String.class),
                record.get("description", String.class),
                instant(record, "created_at")
        );
    }

    private EvaluationComparison comparison(Record record) {
        return new EvaluationComparison(
                record.get("id", UUID.class), record.get("dataset_id", UUID.class),
                record.get("fast_run_id", UUID.class), record.get("deep_run_id", UUID.class),
                record.get("judge_mode", String.class), instant(record, "created_at"));
    }

    private EvaluationCase evaluationCase(Record record) {
        var ids = record.get("expected_document_ids", UUID[].class);
        return new EvaluationCase(
                record.get("id", UUID.class),
                record.get("dataset_id", UUID.class),
                record.get("question", String.class),
                record.get("expected_answer", String.class),
                ids == null ? List.of() : Arrays.asList(ids),
                map(record.get("metadata", String.class)),
                record.get("position", Long.class)
        );
    }

    private EvaluationRun run(Record record) {
        return new EvaluationRun(
                record.get("id", UUID.class),
                record.get("dataset_id", UUID.class),
                EvaluationRunStatus.valueOf(record.get("status", String.class)),
                map(record.get("aggregate_metrics", String.class)),
                nullableInstant(record, "started_at"),
                nullableInstant(record, "completed_at"),
                instant(record, "created_at")
        );
    }

    private EvaluationRun prefixedRun(Record record, String prefix) {
        return new EvaluationRun(
                record.get(prefix + "id", UUID.class),
                record.get(prefix + "dataset_id", UUID.class),
                EvaluationRunStatus.valueOf(record.get(prefix + "status", String.class)),
                map(record.get(prefix + "aggregate_metrics", String.class)),
                nullableInstant(record, prefix + "started_at"),
                nullableInstant(record, prefix + "completed_at"),
                instant(record, prefix + "created_at")
        );
    }

    private EvaluationSchedule schedule(Record record) {
        var notificationId = record.get("last_notification_id", UUID.class);
        return new EvaluationSchedule(
                record.get("id", UUID.class),
                record.get("organization_id", UUID.class),
                record.get("dataset_id", UUID.class),
                record.get("created_by", UUID.class),
                record.get("name", String.class),
                record.get("cadence_minutes", Integer.class),
                Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
                map(record.get("request", String.class)),
                Boolean.TRUE.equals(record.get("webhook_enabled", Boolean.class)),
                record.get("webhook_url", String.class),
                record.get("webhook_secret_ciphertext", String.class),
                notificationId == null ? null : new EvaluationNotificationSummary(
                        notificationId,
                        record.get("last_notification_comparison_id", UUID.class),
                        record.get("last_notification_status", String.class),
                        record.get("last_notification_attempt", Integer.class),
                        record.get("last_notification_max_attempts", Integer.class),
                        record.get("last_notification_response_status", Integer.class),
                        record.get("last_notification_error_message", String.class),
                        instant(record, "last_notification_updated_at")),
                instant(record, "next_run_at"),
                nullableInstant(record, "last_run_at"),
                record.get("last_comparison_id", UUID.class),
                record.get("last_error", String.class),
                instant(record, "created_at"),
                instant(record, "updated_at")
        );
    }

    private EvaluationNotificationDelivery notification(Record record, boolean withRuns) {
        return new EvaluationNotificationDelivery(
                record.get("id", UUID.class),
                record.get("organization_id", UUID.class),
                record.get("schedule_id", UUID.class),
                record.get("comparison_id", UUID.class),
                record.get("dataset_id", UUID.class),
                record.get("schedule_name", String.class),
                record.get("dataset_name", String.class),
                record.get("webhook_url", String.class),
                record.get("webhook_secret_ciphertext", String.class),
                record.get("status", String.class),
                record.get("attempt", Integer.class),
                record.get("max_attempts", Integer.class),
                record.get("response_status", Integer.class),
                record.get("response_body", String.class),
                record.get("error_message", String.class),
                instant(record, "next_attempt_at"),
                nullableInstant(record, "delivered_at"),
                instant(record, "created_at"),
                instant(record, "updated_at"),
                withRuns ? prefixedRun(record, "fast_") : null,
                withRuns ? prefixedRun(record, "deep_") : null
        );
    }

    private java.time.Instant instant(Record record, String field) {
        return record.get(field, OffsetDateTime.class).toInstant();
    }

    private java.time.Instant nullableInstant(Record record, String field) {
        var value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private int count(Record record, String field) {
        var value = record.get(field, Number.class);
        return value == null ? 0 : Math.toIntExact(value.longValue());
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private OffsetDateTime offset(java.time.Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize evaluation value", exception);
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid evaluation JSON", exception);
        }
    }
}
