package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.evaluation.EvaluationCaseAttempt;
import com.yanyue.rag.domain.evaluation.EvaluationRunLineage;
import com.yanyue.rag.domain.port.EvaluationAttemptPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqEvaluationAttemptAdapter implements EvaluationAttemptPort {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqEvaluationAttemptAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveRequestSnapshot(UUID runId, Map<String, Object> requestSnapshot) {
        var updated = dsl.execute("""
                UPDATE evaluation_run
                SET request_snapshot = ?::jsonb,
                    lineage_root_id = COALESCE(lineage_root_id, id)
                WHERE id = ?
                """, json(requestSnapshot), runId);
        if (updated != 1) throw new IllegalArgumentException("Evaluation run not found");
    }

    @Override
    public void linkResumedRun(UUID runId, UUID resumedFromRunId, Map<String, Object> requestSnapshot) {
        var updated = dsl.execute("""
                UPDATE evaluation_run resumed
                SET request_snapshot = ?::jsonb,
                    resumed_from_run_id = previous.id,
                    lineage_root_id = COALESCE(previous.lineage_root_id, previous.id),
                    attempt_number = previous.attempt_number + 1
                FROM evaluation_run previous
                WHERE resumed.id = ?
                  AND previous.id = ?
                  AND resumed.dataset_id = previous.dataset_id
                  AND resumed.id <> previous.id
                """, json(requestSnapshot), runId, resumedFromRunId);
        if (updated != 1) throw new IllegalArgumentException("Evaluation resume lineage is invalid");
    }

    @Override
    public Optional<EvaluationRunLineage> loadLineage(UUID runId) {
        return dsl.fetchOptional("""
                SELECT id, COALESCE(lineage_root_id, id) AS lineage_root_id, resumed_from_run_id,
                       attempt_number, request_snapshot
                FROM evaluation_run
                WHERE id = ?
                """, runId).map(record -> new EvaluationRunLineage(
                record.get("id", UUID.class), record.get("lineage_root_id", UUID.class),
                record.get("resumed_from_run_id", UUID.class), record.get("attempt_number", Integer.class),
                map(record.get("request_snapshot", JSONB.class))
        ));
    }

    @Override
    public void saveCaseAttempt(EvaluationCaseAttempt attempt) {
        dsl.execute("""
                INSERT INTO evaluation_case_attempt
                    (id, evaluation_run_id, evaluation_case_id, rag_run_id, attempt_number, status,
                     previous_attempt_id, metrics, error_message, started_at, completed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::timestamptz, ?::timestamptz,
                        ?::timestamptz)
                ON CONFLICT (evaluation_run_id, evaluation_case_id, attempt_number) DO UPDATE
                SET rag_run_id = EXCLUDED.rag_run_id,
                    status = EXCLUDED.status,
                    previous_attempt_id = EXCLUDED.previous_attempt_id,
                    metrics = EXCLUDED.metrics,
                    error_message = EXCLUDED.error_message,
                    started_at = COALESCE(evaluation_case_attempt.started_at, EXCLUDED.started_at),
                    completed_at = EXCLUDED.completed_at
                """, attempt.id(), attempt.evaluationRunId(), attempt.evaluationCaseId(), attempt.ragRunId(),
                attempt.attemptNumber(), attempt.status(), attempt.previousAttemptId(), json(attempt.metrics()),
                attempt.errorMessage(), attempt.startedAt(), attempt.completedAt(), attempt.createdAt());
    }

    @Override
    public List<EvaluationCaseAttempt> loadCaseAttempts(UUID runId, UUID caseId) {
        return dsl.fetch("""
                SELECT id, evaluation_run_id, evaluation_case_id, rag_run_id, attempt_number, status,
                       previous_attempt_id, metrics, error_message, started_at, completed_at, created_at
                FROM evaluation_case_attempt
                WHERE evaluation_run_id = ? AND evaluation_case_id = ?
                ORDER BY attempt_number
                """, runId, caseId).map(record -> new EvaluationCaseAttempt(
                record.get("id", UUID.class), record.get("evaluation_run_id", UUID.class),
                record.get("evaluation_case_id", UUID.class), record.get("rag_run_id", UUID.class),
                record.get("attempt_number", Integer.class), record.get("status", String.class),
                record.get("previous_attempt_id", UUID.class), map(record.get("metrics", JSONB.class)),
                record.get("error_message", String.class), nullableInstant(record, "started_at"),
                nullableInstant(record, "completed_at"), instant(record, "created_at")
        ));
    }

    private Map<String, Object> map(JSONB value) {
        if (value == null) return Map.of();
        try {
            return objectMapper.readValue(value.data(), MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted evaluation attempt payload is invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize evaluation attempt payload", exception);
        }
    }

    private Instant instant(Record record, String field) {
        return record.get(field, OffsetDateTime.class).toInstant();
    }

    private Instant nullableInstant(Record record, String field) {
        var value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
