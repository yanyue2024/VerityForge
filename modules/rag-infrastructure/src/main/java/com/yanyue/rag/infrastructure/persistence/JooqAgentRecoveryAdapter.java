package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.agent.AgentBudget;
import com.yanyue.rag.domain.agent.AgentRunState;
import com.yanyue.rag.domain.agent.AgentStage;
import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.FactItem;
import com.yanyue.rag.domain.agent.FactStatus;
import com.yanyue.rag.domain.agent.FactSupport;
import com.yanyue.rag.domain.agent.QuestionPlan;
import com.yanyue.rag.domain.port.AgentRecoveryPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class JooqAgentRecoveryAdapter implements AgentRecoveryPort {
    private static final TypeReference<List<MetadataFilter>> FILTERS_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<FactSupport>> SUPPORTS_TYPE = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqAgentRecoveryAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RecoverableRun> findRecoverableRuns() {
        return dsl.fetch("""
                SELECT r.id, r.organization_id, COALESCE(r.created_by, c.created_by) AS user_id,
                       r.conversation_id, r.requested_mode, r.query_text,
                       r.scope, r.filters, r.model_profile_id
                FROM rag_run r
                LEFT JOIN agent_run_checkpoint checkpoint ON checkpoint.run_id = r.id
                LEFT JOIN conversation c ON c.id = r.conversation_id
                WHERE r.status IN ('QUEUED', 'RUNNING')
                  AND r.cancellation_requested = false
                  AND r.pipeline_version <> 'agentic-react-v1'
                  AND COALESCE(checkpoint.checkpoint_version, 1) = 1
                  AND (r.selected_mode = 'DEEP' OR r.requested_mode = 'DEEP')
                ORDER BY r.created_at
                """).map(record -> new RecoverableRun(
                record.get("id", UUID.class), record.get("organization_id", UUID.class),
                record.get("user_id", UUID.class),
                record.get("conversation_id", UUID.class),
                new CreateRunRequest(
                        record.get("query_text", String.class),
                        RunMode.valueOf(record.get("requested_mode", String.class)),
                        read(record.get("scope", JSONB.class), KnowledgeScope.class),
                        read(record.get("filters", JSONB.class), FILTERS_TYPE),
                        record.get("model_profile_id", UUID.class)
                )
        ));
    }

    @Override
    public Optional<RecoverySnapshot> loadSnapshot(UUID runId) {
        var checkpoint = dsl.fetchOptional("""
                SELECT stage, state
                FROM agent_run_checkpoint
                WHERE run_id = ? AND checkpoint_version = 1
                """, runId);
        if (checkpoint.isEmpty()) return Optional.empty();
        var root = tree(checkpoint.get().get("state", JSONB.class));
        var planNode = root.path("plan");
        if (planNode.isMissingNode() || planNode.isNull()) return Optional.empty();
        var plan = convert(planNode, QuestionPlan.class);
        var budget = budget(root.path("budget"));
        var createdAt = instant(root.path("createdAt"));
        var updatedAt = instant(root.path("updatedAt"));
        var state = new AgentRunState(runId,
                AgentStage.valueOf(checkpoint.get().get("stage", String.class)), budget, createdAt, updatedAt);
        var userId = dsl.fetchOptional("""
                SELECT COALESCE(r.created_by, c.created_by) AS user_id
                FROM rag_run r LEFT JOIN conversation c ON c.id = r.conversation_id
                WHERE r.id = ?
                """, runId).map(record -> record.get("user_id", UUID.class)).orElse(null);
        if (userId == null) return Optional.empty();
        var facts = loadFacts(runId, userId);
        var hits = loadEvidenceHits(runId, userId);
        var coverage = dsl.fetchOptional("""
                SELECT report FROM coverage_report WHERE run_id = ? ORDER BY round_number DESC LIMIT 1
                """, runId).map(record -> convert(tree(record.get("report", JSONB.class)), CoverageReport.class))
                .orElse(null);
        dsl.execute("""
                UPDATE agent_retrieval_task
                SET status = 'FAILED', error_message = 'interrupted-by-service-restart', completed_at = now()
                WHERE run_id = ? AND status IN ('PENDING', 'RUNNING')
                """, runId);
        return Optional.of(new RecoverySnapshot(state, plan, facts, hits, coverage));
    }

    @Override
    public void resetIncompleteReasoning(UUID runId) {
        dsl.transaction(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("DELETE FROM agent_retrieval_task WHERE run_id = ?", runId);
            tx.execute("DELETE FROM evidence_item WHERE run_id = ?", runId);
            tx.execute("DELETE FROM fact_item WHERE run_id = ?", runId);
            tx.execute("DELETE FROM coverage_report WHERE run_id = ?", runId);
            tx.execute("DELETE FROM citation WHERE run_id = ?", runId);
            tx.execute("DELETE FROM agent_run_checkpoint WHERE run_id = ?", runId);
            tx.execute("""
                    UPDATE rag_run SET status = 'RUNNING', completed_at = NULL, error_message = NULL,
                        no_answer_reason = NULL WHERE id = ?
                    """, runId);
        });
    }

    private List<FactItem> loadFacts(UUID runId, UUID userId) {
        return dsl.fetch("""
                SELECT id, sub_question_id, statement, evidence_ids, confidence, status,
                       conflict_group_id, supports, rejection_reason
                FROM fact_item fact
                WHERE run_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM unnest(fact.evidence_ids) AS ids(evidence_id)
                      JOIN evidence_item evidence ON evidence.id = ids.evidence_id
                      WHERE NOT document_is_accessible(evidence.document_id, ?)
                  )
                ORDER BY created_at
                """, runId, userId).map(record -> new FactItem(
                record.get("id", UUID.class), record.get("sub_question_id", UUID.class),
                record.get("statement", String.class), List.of(record.get("evidence_ids", UUID[].class)),
                record.get("confidence", java.math.BigDecimal.class).doubleValue(),
                FactStatus.valueOf(record.get("status", String.class)),
                record.get("conflict_group_id", UUID.class),
                read(record.get("supports", JSONB.class), SUPPORTS_TYPE),
                record.get("rejection_reason", String.class)
        ));
    }

    private Map<UUID, RetrievalHit> loadEvidenceHits(UUID runId, UUID userId) {
        var result = new LinkedHashMap<UUID, RetrievalHit>();
        dsl.fetch("""
                SELECT e.id AS evidence_id, e.chunk_id, c.parent_chunk_id, e.document_id,
                       e.document_version_id, d.title AS document_title, e.quote_text,
                       e.retrieval_score, e.retrieval_sources, e.source_start, e.source_end,
                       (SELECT min(db.page_number)
                        FROM document_block db WHERE db.id = ANY(c.source_block_ids)) AS page_number
                FROM evidence_item e
                JOIN chunk c ON c.id = e.chunk_id
                JOIN document d ON d.id = e.document_id
                WHERE e.run_id = ? AND document_is_accessible(e.document_id, ?)
                ORDER BY e.created_at
                """, runId, userId).forEach(record -> result.put(
                record.get("evidence_id", UUID.class),
                new RetrievalHit(
                        record.get("chunk_id", UUID.class), record.get("parent_chunk_id", UUID.class),
                        record.get("document_id", UUID.class), record.get("document_version_id", UUID.class),
                        record.get("document_title", String.class), record.get("quote_text", String.class),
                        record.get("retrieval_score", Double.class),
                        List.of(record.get("retrieval_sources", String[].class)),
                        record.get("page_number", Integer.class), record.get("source_start", Integer.class),
                        record.get("source_end", Integer.class)
                )
        ));
        return Map.copyOf(result);
    }

    private AgentBudget budget(JsonNode value) {
        return new AgentBudget(
                value.path("maxRounds").asInt(4), value.path("maxSubQuestions").asInt(6),
                value.path("maxSearches").asInt(8), value.path("maxDeepReads").asInt(6),
                value.path("maxParallelism").asInt(4), Duration.ofSeconds(Math.round(value.path("timeout").asDouble(120))),
                value.path("roundsUsed").asInt(), value.path("searchesUsed").asInt(),
                value.path("deepReadsUsed").asInt(), instant(value.path("startedAt"))
        );
    }

    private Instant instant(JsonNode value) {
        if (value.isTextual()) return Instant.parse(value.asText());
        var seconds = value.asDouble();
        var whole = (long) seconds;
        return Instant.ofEpochSecond(whole, Math.round((seconds - whole) * 1_000_000_000d));
    }

    private JsonNode tree(JSONB value) {
        try {
            return objectMapper.readTree(value.data());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent checkpoint JSON is invalid", exception);
        }
    }

    private <T> T read(JSONB value, Class<T> type) {
        try {
            return objectMapper.readValue(value.data(), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Run JSON is invalid", exception);
        }
    }

    private <T> T read(JSONB value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value.data(), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Run JSON is invalid", exception);
        }
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        try {
            return objectMapper.treeToValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent checkpoint payload is invalid", exception);
        }
    }
}
