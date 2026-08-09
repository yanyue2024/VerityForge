package com.yanyue.rag.infrastructure.agent.deep.persistence;

import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.ActionStatus;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.BudgetReservation;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.ChunkSourceSegment;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.Evidence;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.EvidenceRequirement;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.ExternalAction;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.GoalResearchOutcome;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.LogicalModelCall;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.ModelAttempt;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.RecoveryState;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.ReservationStatus;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.ResearchPhase;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.RetrievalCandidate;
import static com.yanyue.rag.infrastructure.agent.deep.persistence.DeepPersistenceRecords.RetrievalTask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.deep.AcceptedEvidence;
import com.yanyue.rag.domain.agent.budget.BudgetReservationStatus;
import com.yanyue.rag.domain.agent.deep.ResearchHealth;
import com.yanyue.rag.domain.agent.deep.SearchQuery;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.DeepRunArtifactPort;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class JooqDeepRunPersistenceAdapter implements DeepRunArtifactPort {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqDeepRunPersistenceAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public void checkpoint(UUID runId, String stage, Map<String, Object> state) {
        saveCheckpoint(runId, stage, state);
    }

    @Override
    public void saveEvidence(UUID runId, AcceptedEvidence evidence) {
        var segment = evidence.sourceAnchor().segments().getFirst();
        var persisted = new Evidence(evidence.evidenceId(), runId, evidence.goalId(), evidence.documentId(),
                evidence.documentVersionId(), evidence.parentChunkId(), evidence.spanId(), evidence.quote(),
                segment.documentSourceStart(), segment.documentSourceEnd(), evidence.retrievalScore(),
                ResearchPhase.valueOf(evidence.firstAcceptedPhase().name()),
                objectMapper.convertValue(evidence.sourceAnchor(), new com.fasterxml.jackson.core.type.TypeReference<>() { }),
                evidence.retrievalSources().stream().map(Enum::name).toList());
        var requirements = evidence.requirementLinks().stream().map(link -> new EvidenceRequirement(
                link.requirementId(), ResearchPhase.valueOf(link.acceptedPhase().name()),
                link.repairTargetId(), link.targetEffect() == null ? null : link.targetEffect().name())).toList();
        saveEvidence(persisted, requirements, evidence.querySourceIds().stream().toList());
    }

    @Override
    public void reserveSearch(
            UUID runId,
            com.yanyue.rag.domain.agent.budget.BudgetReservation reservation,
            SearchQuery query
    ) {
        var persistedReservation = new BudgetReservation(reservation.reservationId(), runId,
                reservation.actionKey(), reservation.usage().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue)),
                Map.of(), true, ReservationStatus.RESERVED);
        var actionId = actionId(reservation.reservationId());
        var action = new ExternalAction(actionId, runId, query.goalId(), query.phase().name(), "SEARCH",
                reservation.reservationId(), ActionStatus.PENDING, null);
        var task = new RetrievalTask(query.queryId(), runId, query.goalId(),
                ResearchPhase.valueOf(query.phase().name()), query.role().name(), query.text(),
                query.searchMode().name(), query.targetRequirementIds().stream().toList());
        reserveRetrievalTask(persistedReservation, action, task);
    }

    @Override
    public boolean claimSearch(UUID runId, UUID reservationId) {
        return dsl.transactionResult(configuration -> {
            var tx = DSL.using(configuration);
            if (!claimAction(tx, actionId(reservationId))) return false;
            UUID queryId = queryId(tx, reservationId);
            int updated = tx.execute("""
                    UPDATE agent_retrieval_task
                    SET status = 'RUNNING', started_at = now()
                    WHERE id = ? AND run_id = ? AND status = 'PENDING'
                    """, queryId, runId);
            if (updated != 1) throw new IllegalStateException("检索任务无法进入 RUNNING: " + queryId);
            return true;
        });
    }

    @Override
    public void saveRetrievalCandidates(UUID runId, SearchQuery query, List<RetrievalHit> hits) {
        for (int index = 0; index < hits.size(); index++) {
            var hit = hits.get(index);
            saveRetrievalCandidate(new RetrievalCandidate(query.queryId(), runId, query.goalId(),
                    ResearchPhase.valueOf(query.phase().name()), hit.chunkId(), index + 1, hit.score(),
                    query.searchMode().name(), null, null));
        }
    }

    @Override
    public void completeSearch(
            UUID runId,
            UUID reservationId,
            boolean succeeded,
            int resultCount,
            String errorCategory
    ) {
        dsl.transaction(configuration -> {
            var tx = DSL.using(configuration);
            UUID queryId = queryId(tx, reservationId);
            int taskUpdated = tx.execute("""
                    UPDATE agent_retrieval_task
                    SET status = ?, result_count = ?, error_message = ?, completed_at = now()
                    WHERE id = ? AND run_id = ? AND status IN ('PENDING', 'RUNNING')
                    """, succeeded ? "SUCCEEDED" : "FAILED", Math.max(0, resultCount),
                    errorCategory, queryId, runId);
            if (taskUpdated != 1) throw new IllegalStateException("检索任务已进入终态: " + queryId);
            reconcileAction(tx, actionId(reservationId),
                    succeeded ? ActionStatus.SUCCEEDED : ActionStatus.FAILED,
                    Map.of(), true, errorCategory);
        });
    }

    @Override
    public void reserveOperation(
            UUID runId,
            UUID goalId,
            String phase,
            String operation,
            com.yanyue.rag.domain.agent.budget.BudgetReservation reservation
    ) {
        var persistedReservation = new BudgetReservation(reservation.reservationId(), runId,
                reservation.actionKey(), reservation.usage().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue)),
                Map.of(), true, ReservationStatus.RESERVED);
        var action = new ExternalAction(actionId(reservation.reservationId()), runId, goalId, phase, operation,
                reservation.reservationId(), ActionStatus.PENDING, null);
        dsl.transaction(configuration -> {
            var tx = DSL.using(configuration);
            insertReservation(tx, persistedReservation);
            insertAction(tx, action);
        });
    }

    @Override
    public boolean claimOperation(UUID reservationId) {
        return claimAction(actionId(reservationId));
    }

    @Override
    public void completeOperation(UUID reservationId, boolean succeeded, String errorCategory) {
        reconcileAction(actionId(reservationId), succeeded ? ActionStatus.SUCCEEDED : ActionStatus.FAILED,
                Map.of(), true, errorCategory);
    }

    @Override
    public void reserveModelAttempt(
            UUID runId,
            UUID logicalCallId,
            UUID goalId,
            String phase,
            String operation,
            String promptVersion,
            int attemptNumber,
            com.yanyue.rag.domain.agent.budget.BudgetReservation reservation,
            int promptLength
    ) {
        var persistedReservation = new BudgetReservation(reservation.reservationId(), runId,
                reservation.actionKey(), reservation.usage().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue)),
                Map.of(), true, ReservationStatus.RESERVED);
        var action = new ExternalAction(actionId(reservation.reservationId()), runId, goalId, phase, operation,
                reservation.reservationId(), ActionStatus.PENDING, null);
        var logical = new LogicalModelCall(logicalCallId, runId, goalId, phase, operation, promptVersion,
                "deep-json-contract-v1", sha256(promptVersion + ":" + promptLength), promptLength,
                0, false, 0, 0, 0, ActionStatus.PENDING, null, null);
        var attempt = new ModelAttempt(attemptId(reservation.reservationId()), logicalCallId, attemptNumber,
                reservation.reservationId(), ActionStatus.PENDING,
                reservation.usage().getOrDefault(com.yanyue.rag.domain.agent.budget.BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN, 0L),
                reservation.usage().getOrDefault(com.yanyue.rag.domain.agent.budget.BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN, 0L),
                true, 0, null);
        reserveModelAttempt(persistedReservation, action, logical, attempt);
    }

    @Override
    public boolean claimModelAttempt(UUID reservationId) {
        return dsl.transactionResult(configuration -> {
            var tx = DSL.using(configuration);
            if (!claimAction(tx, actionId(reservationId))) return false;
            Record attempt = tx.fetchOne("""
                    UPDATE agent_model_attempt
                    SET status = 'RUNNING', started_at = now()
                    WHERE reservation_id = ? AND status = 'PENDING'
                    RETURNING logical_call_id
                    """, reservationId);
            if (attempt == null) throw new IllegalStateException("模型 Attempt 无法进入 RUNNING: " + reservationId);
            tx.execute("""
                    UPDATE agent_model_logical_call
                    SET status = 'RUNNING', started_at = COALESCE(started_at, now())
                    WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                    """, attempt.get("logical_call_id", UUID.class));
            return true;
        });
    }

    @Override
    public void completeModelAttempt(
            UUID logicalCallId,
            UUID reservationId,
            int attemptNumber,
            boolean succeeded,
            boolean repairUsed,
            boolean tokenUsageEstimated,
            long inputTokens,
            long outputTokens,
            long latencyMs,
            String errorCategory,
            String resultHash
    ) {
        var status = succeeded ? ActionStatus.SUCCEEDED : ActionStatus.FAILED;
        saveModelAttempt(new ModelAttempt(attemptId(reservationId), logicalCallId, attemptNumber, reservationId,
                status, inputTokens, outputTokens, tokenUsageEstimated, latencyMs, errorCategory));
        reconcileAction(actionId(reservationId), status, Map.of(
                "GENERATIVE_LLM_PHYSICAL_ATTEMPT", 1L,
                "GENERATIVE_LLM_INPUT_TOKEN", inputTokens,
                "GENERATIVE_LLM_OUTPUT_TOKEN", outputTokens), tokenUsageEstimated, errorCategory);
    }

    @Override
    public void completeLogicalModelCall(
            UUID logicalCallId,
            boolean succeeded,
            boolean repairUsed,
            String errorCategory,
            String resultHash
    ) {
        var status = succeeded ? ActionStatus.SUCCEEDED : ActionStatus.FAILED;
        int updated = dsl.execute("""
                UPDATE agent_model_logical_call logical_call
                SET attempt_count = aggregate.attempt_count,
                    repair_used = ?,
                    input_tokens = aggregate.input_tokens,
                    output_tokens = aggregate.output_tokens,
                    latency_ms = aggregate.latency_ms,
                    status = ?,
                    error_category = ?,
                    result_hash = ?,
                    completed_at = now()
                FROM (
                    SELECT logical_call_id,
                           count(*) AS attempt_count,
                           coalesce(sum(input_tokens), 0) AS input_tokens,
                           coalesce(sum(output_tokens), 0) AS output_tokens,
                           coalesce(sum(latency_ms), 0) AS latency_ms
                    FROM agent_model_attempt
                    WHERE logical_call_id = ? AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                    GROUP BY logical_call_id
                ) aggregate
                WHERE logical_call.id = aggregate.logical_call_id
                  AND logical_call.status IN ('PENDING', 'RUNNING')
                """, repairUsed, status.name(), errorCategory, resultHash, logicalCallId);
        if (updated != 1) throw new IllegalStateException("模型逻辑调用已进入终态: " + logicalCallId);
    }

    @Override
    public void saveGoalOutcome(
            UUID runId,
            UUID goalId,
            com.yanyue.rag.domain.agent.deep.ResearchPhase phase,
            ResearchHealth health,
            List<UUID> searchTaskIds,
            UUID deepReadLogicalCallId,
            List<UUID> acceptedEvidenceIds,
            boolean mayHaveHiddenEvidence
    ) {
        saveGoalResearchOutcome(new GoalResearchOutcome(UUID.randomUUID(), runId, goalId,
                ResearchPhase.valueOf(phase.name()), health.mayHideEvidence() ? "FAILED" : "SUCCEEDED",
                searchTaskIds, deepReadLogicalCallId, acceptedEvidenceIds,
                outcomeCategory(health), mayHaveHiddenEvidence, Instant.now()));
    }

    @Override
    public void saveJudgeDecision(UUID runId, boolean sufficient, boolean degraded, Map<String, Object> report) {
        acceptJudgeDecision(runId, null, sufficient, degraded ? "DETERMINISTIC_FALLBACK" : "MODEL", report);
    }

    private String outcomeCategory(ResearchHealth health) {
        return switch (health) {
            case COMPLETED_WITH_EVIDENCE -> "COMPLETED_WITH_EVIDENCE";
            case COMPLETED_EMPTY, SKIPPED_NOT_REQUIRED -> "COMPLETED_EMPTY";
            case DEGRADED_NON_BLOCKING -> "PARTIAL_FAILURE";
            case EVIDENCE_MAY_BE_HIDDEN, SKIPPED_BUDGET -> "EVIDENCE_MAY_BE_HIDDEN";
            case DEADLINE_EXCEEDED -> "DEADLINE_EXCEEDED";
            case CANCELLED -> "CANCELLED";
        };
    }

    private UUID actionId(UUID reservationId) {
        return UUID.nameUUIDFromBytes((reservationId + ":external-action")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private UUID attemptId(UUID reservationId) {
        return UUID.nameUUIDFromBytes((reservationId + ":model-attempt")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public void saveCheckpoint(UUID runId, String stage, Object state) {
        int affected = dsl.execute("""
                INSERT INTO agent_run_checkpoint
                    (run_id, checkpoint_version, stage, state, updated_at)
                VALUES (?, 3, ?, ?::jsonb, now())
                ON CONFLICT (run_id) DO UPDATE
                SET stage = EXCLUDED.stage,
                    state = EXCLUDED.state,
                    updated_at = now()
                WHERE agent_run_checkpoint.checkpoint_version = 3
                """, runId, stage, json(state));
        if (affected != 1) {
            throw new IllegalStateException("不能用 checkpoint v3 覆盖历史 checkpoint");
        }
    }

    public void saveChunkSourceMap(UUID chunkId, boolean mapped, List<ChunkSourceSegment> segments) {
        saveChunkSourceMap(chunkId, mapped, null, segments);
    }

    public void saveChunkSourceMap(com.yanyue.rag.domain.chunking.ChunkSourceMap sourceMap) {
        var segments = sourceMap.segments().stream().map(segment -> new ChunkSourceSegment(
                segment.segmentOrder(), segment.chunkLocalStart(), segment.chunkLocalEnd(),
                segment.documentBlockId(), segment.blockLocalStart(), segment.blockLocalEnd(),
                segment.documentSourceStart(), segment.documentSourceEnd(),
                segment.documentOffsetUnit() == null ? null : segment.documentOffsetUnit().name()
        )).toList();
        String failureReason = sourceMap.status()
                == com.yanyue.rag.domain.chunking.SourceMapStatus.MAPPED
                ? null : sourceMap.failureReason().name();
        saveChunkSourceMap(sourceMap.chunkId(),
                sourceMap.status() == com.yanyue.rag.domain.chunking.SourceMapStatus.MAPPED,
                failureReason, segments);
    }

    private void saveChunkSourceMap(
            UUID chunkId,
            boolean mapped,
            String failureReason,
            List<ChunkSourceSegment> segments
    ) {
        if (mapped && segments.isEmpty()) {
            throw new IllegalArgumentException("MAPPED Chunk 至少需要一个 Source Segment");
        }
        if (!mapped && !segments.isEmpty()) {
            throw new IllegalArgumentException("UNMAPPABLE Chunk 不能持有 Source Segment");
        }
        dsl.transaction(configuration -> {
            var tx = DSL.using(configuration);
            tx.execute("DELETE FROM chunk_source_segment WHERE chunk_id = ?", chunkId);
            for (var segment : segments) {
                tx.execute("""
                        INSERT INTO chunk_source_segment
                            (chunk_id, segment_order, chunk_local_start, chunk_local_end,
                             document_block_id, block_local_start, block_local_end,
                             document_source_start, document_source_end, document_offset_unit)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, chunkId, segment.segmentOrder(), segment.chunkLocalStart(),
                        segment.chunkLocalEnd(), segment.documentBlockId(), segment.blockLocalStart(),
                        segment.blockLocalEnd(), segment.documentSourceStart(), segment.documentSourceEnd(),
                        segment.documentOffsetUnit());
            }
            tx.execute("""
                    UPDATE chunk
                    SET source_mapping_status = ?, source_mapping_failure_reason = ?
                    WHERE id = ?
                    """, mapped ? "MAPPED" : "UNMAPPABLE", failureReason, chunkId);
        });
    }

    public List<ChunkSourceSegment> loadChunkSourceMap(UUID chunkId) {
        return dsl.fetch("""
                SELECT segment_order, chunk_local_start, chunk_local_end, document_block_id,
                       block_local_start, block_local_end, document_source_start, document_source_end,
                       document_offset_unit
                FROM chunk_source_segment
                WHERE chunk_id = ?
                ORDER BY segment_order
                """, chunkId).map(record -> new ChunkSourceSegment(
                record.get("segment_order", Integer.class),
                record.get("chunk_local_start", Integer.class),
                record.get("chunk_local_end", Integer.class),
                record.get("document_block_id", UUID.class),
                record.get("block_local_start", Integer.class),
                record.get("block_local_end", Integer.class),
                record.get("document_source_start", Integer.class),
                record.get("document_source_end", Integer.class),
                record.get("document_offset_unit", String.class)));
    }

    public void reserveRetrievalTask(
            BudgetReservation reservation,
            ExternalAction action,
            RetrievalTask task
    ) {
        requireReservationMatchesAction(reservation, action);
        if (!task.runId().equals(reservation.runId())
                || !task.goalId().equals(action.goalId())) {
            throw new IllegalArgumentException("Retrieval Task 与 Action 不属于同一 Goal");
        }
        dsl.transaction(configuration -> {
            var tx = DSL.using(configuration);
            insertReservation(tx, reservation);
            insertAction(tx, action);
            tx.execute("""
                    INSERT INTO agent_retrieval_task
                        (id, run_id, sub_question_id, round_number, query_text, search_mode, status,
                         research_phase, query_role, normalized_query, target_requirement_ids)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, task.id(), task.runId(), task.goalId(),
                    task.phase() == ResearchPhase.PRIMARY ? 1 : 2,
                    task.queryText(), task.searchMode(), task.phase().name(), task.queryRole(),
                    normalizeQuery(task.queryText()), task.targetRequirementIds().toArray(UUID[]::new));
        });
    }

    public void saveRetrievalCandidate(RetrievalCandidate candidate) {
        dsl.execute("""
                INSERT INTO retrieval_query_candidate
                    (retrieval_task_id, run_id, goal_id, phase, chunk_id, candidate_rank, score,
                     retrieval_source, merged_rank, rerank_score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (retrieval_task_id, chunk_id, retrieval_source) DO UPDATE
                SET candidate_rank = LEAST(retrieval_query_candidate.candidate_rank,
                                           EXCLUDED.candidate_rank),
                    score = GREATEST(retrieval_query_candidate.score, EXCLUDED.score),
                    merged_rank = COALESCE(LEAST(retrieval_query_candidate.merged_rank,
                                                 EXCLUDED.merged_rank),
                                           retrieval_query_candidate.merged_rank,
                                           EXCLUDED.merged_rank),
                    rerank_score = COALESCE(GREATEST(retrieval_query_candidate.rerank_score,
                                                     EXCLUDED.rerank_score),
                                            retrieval_query_candidate.rerank_score,
                                            EXCLUDED.rerank_score)
                """, candidate.retrievalTaskId(), candidate.runId(), candidate.goalId(),
                candidate.phase().name(), candidate.chunkId(), candidate.rank(), candidate.score(),
                candidate.retrievalSource(), candidate.mergedRank(), candidate.rerankScore());
    }

    public UUID saveEvidence(
            Evidence evidence,
            List<EvidenceRequirement> requirements,
            List<UUID> retrievalTaskIds
    ) {
        if (requirements.isEmpty()) {
            throw new IllegalArgumentException("Deep RAG Evidence 至少关联一个 Requirement");
        }
        if (evidence.spanId() == null || !evidence.spanId().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Deep RAG Evidence 必须使用合法的 SHA-256 Span ID");
        }
        return dsl.transactionResult(configuration -> {
            var tx = DSL.using(configuration);
            Record persisted = tx.fetchOne("""
                    INSERT INTO evidence_item
                        (id, run_id, sub_question_id, document_id, document_version_id, chunk_id,
                         quote_text, source_start, source_end, retrieval_score, deep_read,
                         retrieval_sources, span_id, parent_chunk_id, first_accepted_phase, source_anchor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (run_id, sub_question_id, document_version_id, span_id)
                        WHERE span_id IS NOT NULL
                    DO UPDATE SET
                        retrieval_score = GREATEST(evidence_item.retrieval_score,
                                                   EXCLUDED.retrieval_score),
                        retrieval_sources = ARRAY(
                            SELECT DISTINCT source
                            FROM unnest(evidence_item.retrieval_sources
                                        || EXCLUDED.retrieval_sources) AS source
                        )
                    WHERE evidence_item.quote_text = EXCLUDED.quote_text
                      AND evidence_item.parent_chunk_id = EXCLUDED.parent_chunk_id
                    RETURNING id
                    """, evidence.id(), evidence.runId(), evidence.goalId(),
                    evidence.documentId(), evidence.documentVersionId(), evidence.parentChunkId(),
                    evidence.quote(), evidence.sourceStart(), evidence.sourceEnd(), evidence.retrievalScore(),
                    evidence.retrievalSources().toArray(String[]::new), evidence.spanId(),
                    evidence.parentChunkId(), evidence.firstAcceptedPhase().name(),
                    json(evidence.sourceAnchor()));
            if (persisted == null) {
                throw new IllegalStateException("同一 Span 身份对应的原文或父块不一致");
            }
            UUID persistedId = persisted.get("id", UUID.class);
            saveEvidenceRequirements(tx, persistedId, requirements);
            for (UUID taskId : retrievalTaskIds) {
                int linked = tx.execute("""
                        INSERT INTO evidence_query_source (evidence_id, retrieval_task_id)
                        SELECT ?, task.id
                        FROM agent_retrieval_task task
                        WHERE task.id = ? AND task.run_id = ? AND task.sub_question_id = ?
                        ON CONFLICT DO NOTHING
                        """, persistedId, taskId, evidence.runId(), evidence.goalId());
                if (linked != 1 && tx.fetchCount(
                        DSL.table("evidence_query_source"),
                        DSL.field("evidence_id").eq(persistedId)
                                .and(DSL.field("retrieval_task_id").eq(taskId))) != 1) {
                    throw new IllegalArgumentException("Evidence Query 来源不属于同一 Run 和 Goal");
                }
            }
            return persistedId;
        });
    }

    public void saveGoalResearchOutcome(GoalResearchOutcome outcome) {
        dsl.execute("""
                INSERT INTO agent_goal_research_outcome
                    (id, run_id, goal_id, phase, status, search_task_ids,
                     deep_read_logical_call_id, accepted_evidence_ids, outcome_category,
                     may_have_hidden_evidence, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                ON CONFLICT (run_id, goal_id, phase) DO NOTHING
                """, outcome.id(), outcome.runId(), outcome.goalId(), outcome.phase().name(),
                outcome.status(), outcome.searchTaskIds().toArray(UUID[]::new),
                outcome.deepReadLogicalCallId(), outcome.acceptedEvidenceIds().toArray(UUID[]::new),
                outcome.outcomeCategory(), outcome.mayHaveHiddenEvidence(), outcome.completedAt());
    }

    public void reserveModelAttempt(
            BudgetReservation reservation,
            ExternalAction action,
            LogicalModelCall logicalCall,
            ModelAttempt attempt
    ) {
        requireReservationMatchesAction(reservation, action);
        if (!logicalCall.id().equals(attempt.logicalCallId())
                || !logicalCall.runId().equals(reservation.runId())
                || !attempt.reservationId().equals(reservation.id())) {
            throw new IllegalArgumentException("Model Attempt 不属于给定逻辑调用");
        }
        dsl.transaction(configuration -> {
            var tx = DSL.using(configuration);
            insertReservation(tx, reservation);
            insertAction(tx, action);
            upsertLogicalCall(tx, logicalCall);
            upsertModelAttempt(tx, attempt);
        });
    }

    public void saveLogicalModelCall(LogicalModelCall logicalCall) {
        upsertLogicalCall(dsl, logicalCall);
    }

    public void saveModelAttempt(ModelAttempt attempt) {
        upsertModelAttempt(dsl, attempt);
    }

    public boolean claimAction(UUID actionId) {
        return dsl.transactionResult(configuration -> claimAction(DSL.using(configuration), actionId));
    }

    public void reconcileAction(
            UUID actionId,
            ActionStatus finalStatus,
            Map<String, Long> actualUsage,
            boolean usageEstimated,
            String errorCategory
    ) {
        if (finalStatus != ActionStatus.SUCCEEDED && finalStatus != ActionStatus.FAILED
                && finalStatus != ActionStatus.CANCELLED) {
            throw new IllegalArgumentException("Action 只能归约到终态");
        }
        dsl.transaction(configuration -> reconcileAction(DSL.using(configuration), actionId,
                finalStatus, actualUsage, usageEstimated, errorCategory));
    }

    private boolean claimAction(DSLContext tx, UUID actionId) {
        Record action = tx.fetchOne("""
                SELECT reservation_id FROM agent_external_action
                WHERE id = ? AND status = 'PENDING'
                FOR UPDATE
                """, actionId);
        UUID reservationId = action == null ? null : action.get("reservation_id", UUID.class);
        if (reservationId == null) return false;
        int reservationUpdated = tx.execute("""
                UPDATE agent_budget_reservation
                SET status = 'DISPATCHED', dispatched_at = now()
                WHERE id = ? AND status = 'RESERVED'
                """, reservationId);
        if (reservationUpdated != 1) return false;
        return tx.execute("""
                UPDATE agent_external_action
                SET status = 'RUNNING', started_at = now()
                WHERE id = ? AND status = 'PENDING'
                """, actionId) == 1;
    }

    private void reconcileAction(
            DSLContext tx,
            UUID actionId,
            ActionStatus finalStatus,
            Map<String, Long> actualUsage,
            boolean usageEstimated,
            String errorCategory
    ) {
        Record action = tx.fetchOne(
                "SELECT reservation_id FROM agent_external_action WHERE id = ? FOR UPDATE",
                actionId);
        UUID reservationId = action == null ? null : action.get("reservation_id", UUID.class);
        if (reservationId == null) throw new IllegalArgumentException("未知 Action: " + actionId);
        tx.execute("""
                UPDATE agent_external_action
                SET status = ?, error_category = ?, completed_at = now()
                WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                """, finalStatus.name(), errorCategory, actionId);
        tx.execute("""
                UPDATE agent_budget_reservation
                SET status = ?, completed_at = now()
                WHERE id = ? AND status IN ('RESERVED', 'DISPATCHED')
                """, finalStatus == ActionStatus.SUCCEEDED ? "SUCCEEDED" : "FAILED", reservationId);
        tx.execute("""
                UPDATE agent_budget_reservation_usage
                SET actual_amount = reserved_amount, estimated = ?
                WHERE reservation_id = ?
                """, usageEstimated, reservationId);
        if (actualUsage == null) return;
        for (var usage : actualUsage.entrySet()) {
            int updated = tx.execute("""
                    UPDATE agent_budget_reservation_usage
                    SET actual_amount = ?, estimated = ?
                    WHERE reservation_id = ? AND dimension = ?
                    """, usage.getValue(), usageEstimated, reservationId, usage.getKey());
            if (updated != 1) {
                throw new IllegalArgumentException("实际用量包含未预留维度: " + usage.getKey());
            }
        }
    }

    private UUID queryId(DSLContext tx, UUID reservationId) {
        Record reservation = tx.fetchOne(
                "SELECT action_key FROM agent_budget_reservation WHERE id = ?", reservationId);
        String actionKey = reservation == null ? null : reservation.get("action_key", String.class);
        if (actionKey == null || !actionKey.startsWith("search:")) {
            throw new IllegalArgumentException("检索预留缺少合法 Query ID: " + reservationId);
        }
        try {
            return UUID.fromString(actionKey.substring("search:".length()));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("检索预留缺少合法 Query ID: " + reservationId, failure);
        }
    }

    public void acceptJudgeDecision(
            UUID runId,
            UUID logicalCallId,
            boolean sufficient,
            String decisionSource,
            Object report
    ) {
        int affected = dsl.execute("""
                INSERT INTO coverage_report
                    (run_id, round_number, sufficient, report, decision_source,
                     logical_call_id, decision_schema_version)
                VALUES (?, 1, ?, ?::jsonb, ?, ?, 4)
                ON CONFLICT (run_id, round_number) DO UPDATE
                SET sufficient = EXCLUDED.sufficient,
                    report = EXCLUDED.report,
                    decision_source = EXCLUDED.decision_source,
                    logical_call_id = EXCLUDED.logical_call_id,
                    decision_schema_version = 4,
                    created_at = now()
                WHERE coverage_report.decision_schema_version = 4
                  AND coverage_report.logical_call_id IS NOT DISTINCT FROM EXCLUDED.logical_call_id
                """, runId, sufficient, json(report), decisionSource, logicalCallId);
        if (affected != 1) {
            throw new IllegalStateException("同一 Run 只能接受一个稳定的 Deep RAG Judge 决策");
        }
    }

    public void finishRun(
            UUID runId,
            String status,
            String answerMode,
            String stopReason,
            String noAnswerReason
    ) {
        int affected = dsl.execute("""
                UPDATE rag_run
                SET status = ?, answer_mode = ?, stop_reason = ?, no_answer_reason = ?,
                    completed_at = now()
                WHERE id = ? AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """, status, answerMode, stopReason, noAnswerReason, runId);
        if (affected != 1) {
            throw new IllegalStateException("Run 已进入终态，拒绝晚到回调覆盖");
        }
    }

    public RecoveryState loadRecoveryState(UUID runId) {
        return new RecoveryState(
                loadGoalResearchOutcomes(runId),
                loadReservations(runId),
                loadActions(runId),
                loadLogicalCalls(runId),
                loadAttempts(runId));
    }

    private List<GoalResearchOutcome> loadGoalResearchOutcomes(UUID runId) {
        return dsl.fetch("""
                SELECT id, run_id, goal_id, phase, status, search_task_ids,
                       deep_read_logical_call_id, accepted_evidence_ids, outcome_category,
                       may_have_hidden_evidence, completed_at
                FROM agent_goal_research_outcome
                WHERE run_id = ?
                ORDER BY phase, goal_id
                """, runId).map(record -> new GoalResearchOutcome(
                uuid(record, "id"), uuid(record, "run_id"), uuid(record, "goal_id"),
                ResearchPhase.valueOf(record.get("phase", String.class)),
                record.get("status", String.class), uuidList(record, "search_task_ids"),
                record.get("deep_read_logical_call_id", UUID.class),
                uuidList(record, "accepted_evidence_ids"),
                record.get("outcome_category", String.class),
                Boolean.TRUE.equals(record.get("may_have_hidden_evidence", Boolean.class)),
                record.get("completed_at", Instant.class)));
    }

    private List<BudgetReservation> loadReservations(UUID runId) {
        return dsl.fetch("""
                SELECT id, run_id, action_key, status
                FROM agent_budget_reservation WHERE run_id = ? ORDER BY created_at, id
                """, runId).map(record -> {
                    UUID reservationId = uuid(record, "id");
                    var usageRecords = dsl.fetch("""
                            SELECT dimension, reserved_amount, actual_amount, estimated
                            FROM agent_budget_reservation_usage
                            WHERE reservation_id = ? ORDER BY dimension
                            """, reservationId);
                    var reserved = new java.util.LinkedHashMap<String, Long>();
                    var actual = new java.util.LinkedHashMap<String, Long>();
                    boolean estimated = false;
                    for (var usage : usageRecords) {
                        String dimension = usage.get("dimension", String.class);
                        reserved.put(dimension, usage.get("reserved_amount", Long.class));
                        Long actualAmount = usage.get("actual_amount", Long.class);
                        if (actualAmount != null) actual.put(dimension, actualAmount);
                        estimated |= Boolean.TRUE.equals(usage.get("estimated", Boolean.class));
                    }
                    return new BudgetReservation(
                            reservationId, uuid(record, "run_id"), record.get("action_key", String.class),
                            Map.copyOf(reserved), Map.copyOf(actual), estimated,
                            ReservationStatus.valueOf(record.get("status", String.class)));
                });
    }

    private List<ExternalAction> loadActions(UUID runId) {
        return dsl.fetch("""
                SELECT id, run_id, goal_id, phase, operation, reservation_id, status, error_category
                FROM agent_external_action WHERE run_id = ? ORDER BY created_at, id
                """, runId).map(record -> new ExternalAction(
                uuid(record, "id"), uuid(record, "run_id"), record.get("goal_id", UUID.class),
                record.get("phase", String.class), record.get("operation", String.class),
                uuid(record, "reservation_id"), ActionStatus.valueOf(record.get("status", String.class)),
                record.get("error_category", String.class)));
    }

    private List<LogicalModelCall> loadLogicalCalls(UUID runId) {
        return dsl.fetch("""
                SELECT id, run_id, goal_id, phase, operation, prompt_version, contract_version,
                       prompt_hash, prompt_length, attempt_count, repair_used, input_tokens,
                       output_tokens, latency_ms, status, error_category, result_hash
                FROM agent_model_logical_call WHERE run_id = ? ORDER BY created_at, id
                """, runId).map(record -> new LogicalModelCall(
                uuid(record, "id"), uuid(record, "run_id"), record.get("goal_id", UUID.class),
                record.get("phase", String.class), record.get("operation", String.class),
                record.get("prompt_version", String.class), record.get("contract_version", String.class),
                record.get("prompt_hash", String.class), record.get("prompt_length", Integer.class),
                record.get("attempt_count", Integer.class),
                Boolean.TRUE.equals(record.get("repair_used", Boolean.class)),
                record.get("input_tokens", Long.class), record.get("output_tokens", Long.class),
                record.get("latency_ms", Long.class),
                ActionStatus.valueOf(record.get("status", String.class)),
                record.get("error_category", String.class), record.get("result_hash", String.class)));
    }

    private List<ModelAttempt> loadAttempts(UUID runId) {
        return dsl.fetch("""
                SELECT attempt.id, attempt.logical_call_id, attempt.attempt_number,
                       attempt.reservation_id, attempt.status, attempt.input_tokens,
                       attempt.output_tokens, attempt.token_usage_estimated, attempt.latency_ms,
                       attempt.error_category
                FROM agent_model_attempt attempt
                JOIN agent_model_logical_call logical_call
                  ON logical_call.id = attempt.logical_call_id
                WHERE logical_call.run_id = ?
                ORDER BY logical_call.created_at, attempt.attempt_number
                """, runId).map(record -> new ModelAttempt(
                uuid(record, "id"), uuid(record, "logical_call_id"),
                record.get("attempt_number", Integer.class), uuid(record, "reservation_id"),
                ActionStatus.valueOf(record.get("status", String.class)),
                record.get("input_tokens", Long.class), record.get("output_tokens", Long.class),
                Boolean.TRUE.equals(record.get("token_usage_estimated", Boolean.class)),
                record.get("latency_ms", Long.class), record.get("error_category", String.class)));
    }

    private void saveEvidenceRequirements(
            DSLContext tx,
            UUID evidenceId,
            List<EvidenceRequirement> requirements
    ) {
        for (var requirement : requirements) {
            tx.execute("""
                    INSERT INTO evidence_requirement
                        (evidence_id, requirement_id, accepted_phase, repair_target_id, target_effect)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (evidence_id, requirement_id) DO UPDATE
                    SET accepted_phase = CASE
                            WHEN EXCLUDED.accepted_phase = 'REPAIR' THEN 'REPAIR'
                            ELSE evidence_requirement.accepted_phase
                        END,
                        target_effect = CASE
                            WHEN evidence_requirement.target_effect = 'COMPLETE' THEN 'COMPLETE'
                            WHEN EXCLUDED.target_effect = 'COMPLETE' THEN 'COMPLETE'
                            ELSE COALESCE(EXCLUDED.target_effect,
                                          evidence_requirement.target_effect)
                        END,
                        repair_target_id = COALESCE(evidence_requirement.repair_target_id,
                                                    EXCLUDED.repair_target_id),
                        updated_at = now()
                    """, evidenceId, requirement.requirementId(), requirement.acceptedPhase().name(),
                    requirement.repairTargetId(), requirement.targetEffect());
        }
    }

    private void insertReservation(DSLContext tx, BudgetReservation reservation) {
        tx.execute("""
                INSERT INTO agent_budget_reservation
                    (id, run_id, action_key, status)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, reservation.id(), reservation.runId(), reservation.actionKey(),
                reservation.status().name());
        for (var usage : reservation.reservedUsage().entrySet()) {
            tx.execute("""
                    INSERT INTO agent_budget_reservation_usage
                        (reservation_id, dimension, reserved_amount, actual_amount, estimated)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (reservation_id, dimension) DO NOTHING
                    """, reservation.id(), usage.getKey(), usage.getValue(),
                    reservation.actualUsage().get(usage.getKey()), reservation.usageEstimated());
        }
    }

    private void insertAction(DSLContext tx, ExternalAction action) {
        tx.execute("""
                INSERT INTO agent_external_action
                    (id, run_id, goal_id, phase, operation, reservation_id, status, error_category)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, action.id(), action.runId(), action.goalId(), action.phase(), action.operation(),
                action.reservationId(), action.status().name(), action.errorCategory());
    }

    private void upsertLogicalCall(DSLContext context, LogicalModelCall call) {
        context.execute("""
                INSERT INTO agent_model_logical_call
                    (id, run_id, goal_id, phase, operation, prompt_version, contract_version,
                     prompt_hash, prompt_length, attempt_count, repair_used, input_tokens,
                     output_tokens, latency_ms, status, error_category, result_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE
                SET attempt_count = GREATEST(agent_model_logical_call.attempt_count,
                                             EXCLUDED.attempt_count),
                    repair_used = agent_model_logical_call.repair_used OR EXCLUDED.repair_used,
                    input_tokens = CASE
                        WHEN agent_model_logical_call.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_logical_call.input_tokens
                        ELSE GREATEST(agent_model_logical_call.input_tokens, EXCLUDED.input_tokens)
                    END,
                    output_tokens = CASE
                        WHEN agent_model_logical_call.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_logical_call.output_tokens
                        ELSE GREATEST(agent_model_logical_call.output_tokens, EXCLUDED.output_tokens)
                    END,
                    latency_ms = CASE
                        WHEN agent_model_logical_call.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_logical_call.latency_ms
                        ELSE GREATEST(agent_model_logical_call.latency_ms, EXCLUDED.latency_ms)
                    END,
                    status = CASE
                        WHEN agent_model_logical_call.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_logical_call.status
                        WHEN agent_model_logical_call.status = 'RUNNING' AND EXCLUDED.status = 'PENDING'
                        THEN agent_model_logical_call.status
                        ELSE EXCLUDED.status
                    END,
                    error_category = CASE
                        WHEN agent_model_logical_call.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_logical_call.error_category
                        ELSE EXCLUDED.error_category
                    END,
                    result_hash = COALESCE(agent_model_logical_call.result_hash,
                                           EXCLUDED.result_hash),
                    started_at = CASE
                        WHEN EXCLUDED.status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN COALESCE(agent_model_logical_call.started_at, now())
                        ELSE agent_model_logical_call.started_at
                    END,
                    completed_at = CASE
                        WHEN agent_model_logical_call.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_logical_call.completed_at
                        WHEN EXCLUDED.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN now()
                        ELSE agent_model_logical_call.completed_at
                    END
                """, call.id(), call.runId(), call.goalId(), call.phase(), call.operation(),
                call.promptVersion(), call.contractVersion(), call.promptHash(), call.promptLength(),
                call.attemptCount(), call.repairUsed(), call.inputTokens(), call.outputTokens(),
                call.latencyMs(), call.status().name(), call.errorCategory(), call.resultHash());
    }

    private void upsertModelAttempt(DSLContext context, ModelAttempt attempt) {
        context.execute("""
                INSERT INTO agent_model_attempt
                    (id, logical_call_id, attempt_number, reservation_id, status, input_tokens,
                     output_tokens, token_usage_estimated, latency_ms, error_category,
                     started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        CASE WHEN ? IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
                             THEN now() ELSE NULL END,
                        CASE WHEN ? IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                             THEN now() ELSE NULL END)
                ON CONFLICT (logical_call_id, attempt_number) DO UPDATE
                SET status = CASE
                        WHEN agent_model_attempt.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_attempt.status
                        ELSE EXCLUDED.status
                    END,
                    input_tokens = CASE
                        WHEN agent_model_attempt.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_attempt.input_tokens
                        WHEN EXCLUDED.token_usage_estimated
                        THEN GREATEST(agent_model_attempt.input_tokens, EXCLUDED.input_tokens)
                        ELSE EXCLUDED.input_tokens
                    END,
                    output_tokens = CASE
                        WHEN agent_model_attempt.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_attempt.output_tokens
                        WHEN EXCLUDED.token_usage_estimated
                        THEN GREATEST(agent_model_attempt.output_tokens, EXCLUDED.output_tokens)
                        ELSE EXCLUDED.output_tokens
                    END,
                    token_usage_estimated = CASE
                        WHEN agent_model_attempt.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_attempt.token_usage_estimated
                        ELSE EXCLUDED.token_usage_estimated
                    END,
                    latency_ms = CASE
                        WHEN agent_model_attempt.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_attempt.latency_ms
                        ELSE GREATEST(agent_model_attempt.latency_ms, EXCLUDED.latency_ms)
                    END,
                    error_category = CASE
                        WHEN agent_model_attempt.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_attempt.error_category
                        ELSE EXCLUDED.error_category
                    END,
                    started_at = COALESCE(agent_model_attempt.started_at, EXCLUDED.started_at),
                    completed_at = CASE
                        WHEN agent_model_attempt.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN agent_model_attempt.completed_at
                        ELSE EXCLUDED.completed_at
                    END
                """, attempt.id(), attempt.logicalCallId(), attempt.attemptNumber(),
                attempt.reservationId(), attempt.status().name(), attempt.inputTokens(),
                attempt.outputTokens(), attempt.tokenUsageEstimated(), attempt.latencyMs(),
                attempt.errorCategory(), attempt.status().name(), attempt.status().name());
    }

    private void requireReservationMatchesAction(
            BudgetReservation reservation,
            ExternalAction action
    ) {
        if (!reservation.id().equals(action.reservationId())
                || !reservation.runId().equals(action.runId())
                || reservation.reservedUsage().isEmpty()
                || reservation.status() != ReservationStatus.RESERVED
                || action.status() != ActionStatus.PENDING) {
            throw new IllegalArgumentException("Action 与预算预留不属于同一 Run");
        }
    }

    private String normalizeQuery(String query) {
        return query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private UUID uuid(Record record, String fieldName) {
        return record.get(fieldName, UUID.class);
    }

    private List<UUID> uuidList(Record record, String fieldName) {
        UUID[] values = record.get(fieldName, UUID[].class);
        return values == null ? List.of() : List.of(values);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化 Deep RAG Artifact", exception);
        }
    }
}
