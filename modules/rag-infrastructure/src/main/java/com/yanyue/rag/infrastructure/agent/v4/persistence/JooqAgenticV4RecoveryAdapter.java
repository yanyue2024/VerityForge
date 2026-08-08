package com.yanyue.rag.infrastructure.agent.v4.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.BudgetReservation;
import com.yanyue.rag.domain.agent.v4.BudgetReservationStatus;
import com.yanyue.rag.domain.agent.v4.EvidenceLinkStatus;
import com.yanyue.rag.domain.agent.v4.EvidenceRequirementLink;
import com.yanyue.rag.domain.agent.v4.ResearchHealth;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v4.TargetEffect;
import com.yanyue.rag.domain.chunking.v4.SourceAnchor;
import com.yanyue.rag.domain.port.AgenticV4RecoveryPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class JooqAgenticV4RecoveryAdapter implements AgenticV4RecoveryPort {
    private static final TypeReference<List<MetadataFilter>> FILTERS_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqAgenticV4RecoveryAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RecoverableRun> findRecoverableRuns() {
        return dsl.fetch("""
                SELECT run.id, run.organization_id,
                       coalesce(run.created_by, conversation.created_by) AS user_id,
                       run.conversation_id, run.requested_mode, run.query_text,
                       run.scope, run.filters, run.model_profile_id
                FROM rag_run run
                JOIN agent_run_checkpoint checkpoint ON checkpoint.run_id = run.id
                    AND checkpoint.checkpoint_version = 3
                LEFT JOIN conversation ON conversation.id = run.conversation_id
                WHERE run.status IN ('QUEUED', 'RUNNING')
                  AND run.cancellation_requested = false
                  AND run.pipeline_version = 'agentic-rag-v4'
                ORDER BY run.created_at
                """).map(record -> new RecoverableRun(
                record.get("id", UUID.class), record.get("organization_id", UUID.class),
                record.get("user_id", UUID.class), record.get("conversation_id", UUID.class),
                new CreateRunRequest(record.get("query_text", String.class),
                        RunMode.valueOf(record.get("requested_mode", String.class)),
                        read(record.get("scope", JSONB.class), KnowledgeScope.class),
                        read(record.get("filters", JSONB.class), FILTERS_TYPE),
                        record.get("model_profile_id", UUID.class))));
    }

    @Override
    public Optional<RecoverySnapshot> loadSnapshot(UUID runId) {
        var checkpoint = dsl.fetchOptional("""
                SELECT checkpoint.stage, checkpoint.state, run.started_at
                FROM agent_run_checkpoint checkpoint
                JOIN rag_run run ON run.id = checkpoint.run_id
                WHERE checkpoint.run_id = ? AND checkpoint.checkpoint_version = 3
                  AND run.pipeline_version = 'agentic-rag-v4'
                """, runId);
        if (checkpoint.isEmpty()) return Optional.empty();
        var record = checkpoint.orElseThrow();
        return Optional.of(new RecoverySnapshot(
                record.get("stage", String.class), read(record.get("state", JSONB.class), MAP_TYPE),
                record.get("started_at", Instant.class), loadEvidence(runId), loadOutcomes(runId),
                loadReservations(runId), loadNonReplayableActions(runId), loadJudgeReport(runId)));
    }

    @Override
    public void prepareForRecovery(UUID runId) {
        dsl.transaction(configuration -> {
            var tx = DSL.using(configuration);
            tx.execute("""
                    UPDATE agent_retrieval_task
                    SET status = 'FAILED', error_message = 'interrupted-by-service-restart', completed_at = now()
                    WHERE run_id = ? AND status = 'RUNNING'
                    """, runId);
            tx.execute("""
                    UPDATE agent_model_attempt attempt
                    SET status = 'FAILED', error_category = 'INTERRUPTED_BY_RESTART', completed_at = now()
                    FROM agent_model_logical_call logical_call
                    WHERE attempt.logical_call_id = logical_call.id AND logical_call.run_id = ?
                      AND attempt.status = 'RUNNING'
                    """, runId);
            tx.execute("""
                    UPDATE agent_model_logical_call
                    SET status = 'FAILED', error_category = 'INTERRUPTED_BY_RESTART', completed_at = now()
                    WHERE run_id = ? AND status = 'RUNNING'
                    """, runId);
            tx.execute("""
                    UPDATE agent_external_action
                    SET status = 'FAILED', error_category = 'INTERRUPTED_BY_RESTART', completed_at = now()
                    WHERE run_id = ? AND status = 'RUNNING'
                    """, runId);
            tx.execute("""
                    UPDATE agent_budget_reservation
                    SET status = 'FAILED', completed_at = now()
                    WHERE run_id = ? AND status = 'DISPATCHED'
                    """, runId);
        });
    }

    private List<AcceptedEvidence> loadEvidence(UUID runId) {
        return dsl.fetch("""
                SELECT evidence.id, evidence.sub_question_id, evidence.span_id,
                       evidence.document_id, evidence.document_version_id, evidence.parent_chunk_id,
                       evidence.quote_text, evidence.source_anchor, document.title,
                       evidence.retrieval_score, evidence.first_accepted_phase,
                       evidence.retrieval_sources,
                       coalesce(array_agg(DISTINCT source.retrieval_task_id)
                           FILTER (WHERE source.retrieval_task_id IS NOT NULL), ARRAY[]::uuid[]) AS query_ids
                FROM evidence_item evidence
                JOIN document ON document.id = evidence.document_id
                LEFT JOIN evidence_query_source source ON source.evidence_id = evidence.id
                WHERE evidence.run_id = ? AND evidence.span_id IS NOT NULL
                GROUP BY evidence.id, document.title
                ORDER BY evidence.created_at, evidence.id
                """, runId).map(record -> {
            UUID evidenceId = record.get("id", UUID.class);
            var anchor = read(record.get("source_anchor", JSONB.class), SourceAnchor.class);
            var pageNumbers = anchor.segments().stream().map(segment -> segment.pageNumber())
                    .filter(java.util.Objects::nonNull).distinct().sorted().toList();
            String pageRange = pageNumbers.isEmpty() ? "" : pageNumbers.getFirst()
                    + (pageNumbers.size() == 1 ? "" : "-" + pageNumbers.getLast());
            var retrievalSources = new LinkedHashSet<SearchMode>();
            String[] sourceNames = record.get("retrieval_sources", String[].class);
            if (sourceNames != null) Arrays.stream(sourceNames).map(String::toUpperCase)
                    .map(SearchMode::valueOf).forEach(retrievalSources::add);
            return new AcceptedEvidence(evidenceId, record.get("sub_question_id", UUID.class),
                    loadRequirementLinks(evidenceId), record.get("span_id", String.class).strip(),
                    record.get("document_id", UUID.class), record.get("document_version_id", UUID.class),
                    record.get("parent_chunk_id", UUID.class), record.get("quote_text", String.class), anchor,
                    record.get("title", String.class), pageRange,
                    record.get("retrieval_score", Double.class),
                    ResearchPhase.valueOf(record.get("first_accepted_phase", String.class)),
                    new LinkedHashSet<>(Arrays.asList(record.get("query_ids", UUID[].class))), retrievalSources);
        });
    }

    private List<EvidenceRequirementLink> loadRequirementLinks(UUID evidenceId) {
        return dsl.fetch("""
                SELECT requirement_id, accepted_phase, repair_target_id, target_effect, status
                FROM evidence_requirement WHERE evidence_id = ? ORDER BY created_at, requirement_id
                """, evidenceId).map(record -> new EvidenceRequirementLink(
                record.get("requirement_id", UUID.class),
                ResearchPhase.valueOf(record.get("accepted_phase", String.class)),
                record.get("repair_target_id", UUID.class),
                record.get("target_effect", String.class) == null ? null
                        : TargetEffect.valueOf(record.get("target_effect", String.class)),
                EvidenceLinkStatus.valueOf(record.get("status", String.class))));
    }

    private List<GoalOutcome> loadOutcomes(UUID runId) {
        return dsl.fetch("""
                SELECT goal_id, phase, outcome_category, accepted_evidence_ids, may_have_hidden_evidence
                FROM agent_goal_research_outcome WHERE run_id = ? ORDER BY completed_at, goal_id
                """, runId).map(record -> new GoalOutcome(record.get("goal_id", UUID.class),
                ResearchPhase.valueOf(record.get("phase", String.class)),
                health(record.get("outcome_category", String.class)),
                Arrays.asList(record.get("accepted_evidence_ids", UUID[].class)),
                Boolean.TRUE.equals(record.get("may_have_hidden_evidence", Boolean.class))));
    }

    private List<BudgetReservation> loadReservations(UUID runId) {
        return dsl.fetch("""
                SELECT id, action_key, status, created_at,
                       coalesce(completed_at, dispatched_at, created_at) AS updated_at
                FROM agent_budget_reservation WHERE run_id = ? ORDER BY created_at, id
                """, runId).map(record -> {
            UUID reservationId = record.get("id", UUID.class);
            var usage = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
            dsl.fetch("""
                    SELECT dimension, reserved_amount, actual_amount
                    FROM agent_budget_reservation_usage WHERE reservation_id = ?
                    """, reservationId).forEach(value -> {
                Long actual = value.get("actual_amount", Long.class);
                long amount = actual == null ? value.get("reserved_amount", Long.class) : actual;
                if (amount > 0) usage.put(BudgetDimension.valueOf(value.get("dimension", String.class)), amount);
            });
            return new BudgetReservation(reservationId, record.get("action_key", String.class), usage,
                    BudgetReservationStatus.valueOf(record.get("status", String.class)),
                    record.get("created_at", Instant.class), record.get("updated_at", Instant.class));
        });
    }

    private java.util.Set<String> loadNonReplayableActions(UUID runId) {
        return new LinkedHashSet<>(dsl.fetch("""
                SELECT reservation.action_key
                FROM agent_external_action action
                JOIN agent_budget_reservation reservation ON reservation.id = action.reservation_id
                WHERE action.run_id = ? AND action.status <> 'PENDING'
                ORDER BY action.created_at
                """, runId).getValues("action_key", String.class));
    }

    private Map<String, Object> loadJudgeReport(UUID runId) {
        return dsl.fetchOptional("""
                SELECT report FROM coverage_report
                WHERE run_id = ? AND decision_schema_version = 4
                """, runId).map(record -> read(record.get("report", JSONB.class), MAP_TYPE)).orElse(Map.of());
    }

    private ResearchHealth health(String category) {
        return switch (category) {
            case "COMPLETED_WITH_EVIDENCE" -> ResearchHealth.COMPLETED_WITH_EVIDENCE;
            case "COMPLETED_EMPTY" -> ResearchHealth.COMPLETED_EMPTY;
            case "PARTIAL_FAILURE" -> ResearchHealth.DEGRADED_NON_BLOCKING;
            case "DEADLINE_EXCEEDED" -> ResearchHealth.DEADLINE_EXCEEDED;
            case "CANCELLED" -> ResearchHealth.CANCELLED;
            default -> ResearchHealth.EVIDENCE_MAY_BE_HIDDEN;
        };
    }

    private <T> T read(JSONB value, Class<T> type) {
        try {
            return objectMapper.readValue(value.data(), type);
        } catch (Exception failure) {
            throw new IllegalStateException("无法读取 Agentic RAG v4 恢复数据", failure);
        }
    }

    private <T> T read(JSONB value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value.data(), type);
        } catch (Exception failure) {
            throw new IllegalStateException("无法读取 Agentic RAG v4 恢复数据", failure);
        }
    }
}
