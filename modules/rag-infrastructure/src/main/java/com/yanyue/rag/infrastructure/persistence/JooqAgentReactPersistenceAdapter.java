package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.agent.react.KnowledgeReferenceSource;
import com.yanyue.rag.domain.agent.react.ReactCheckpoint;
import com.yanyue.rag.domain.agent.react.ReactKnowledgeReference;
import com.yanyue.rag.domain.agent.react.ReactRankedDocument;
import com.yanyue.rag.domain.agent.react.ReactRecoverableRun;
import com.yanyue.rag.domain.agent.react.ReactRunArtifacts;
import com.yanyue.rag.domain.agent.react.ReactStep;
import com.yanyue.rag.domain.agent.react.ReactStepStatus;
import com.yanyue.rag.domain.agent.react.ReactToolCall;
import com.yanyue.rag.domain.agent.react.ReactToolCallStatus;
import com.yanyue.rag.domain.port.AgentReactPersistencePort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqAgentReactPersistenceAdapter implements AgentReactPersistencePort {
    private static final TypeReference<List<MetadataFilter>> FILTERS_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqAgentReactPersistenceAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ReactRecoverableRun> findRecoverableRuns() {
        return dsl.fetch("""
                SELECT run.id, run.organization_id, COALESCE(run.created_by, conversation.created_by) AS user_id,
                       run.conversation_id, run.requested_mode, run.query_text, run.scope, run.filters,
                       run.model_profile_id
                FROM rag_run run
                JOIN agent_run_checkpoint checkpoint ON checkpoint.run_id = run.id
                                                    AND checkpoint.checkpoint_version = 2
                LEFT JOIN conversation ON conversation.id = run.conversation_id
                WHERE run.status IN ('QUEUED', 'RUNNING')
                  AND run.cancellation_requested = false
                  AND run.pipeline_version = 'agentic-react-v1'
                  AND (run.selected_mode = 'DEEP' OR run.requested_mode = 'DEEP')
                ORDER BY run.created_at
                """).map(record -> new ReactRecoverableRun(
                record.get("id", UUID.class),
                record.get("organization_id", UUID.class),
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
    public void saveCheckpoint(ReactCheckpoint checkpoint) {
        dsl.execute("""
                INSERT INTO agent_run_checkpoint (run_id, checkpoint_version, stage, state, updated_at)
                VALUES (?, 2, ?, ?::jsonb, now())
                ON CONFLICT (run_id) DO UPDATE
                SET checkpoint_version = 2,
                    stage = EXCLUDED.stage,
                    state = EXCLUDED.state,
                    updated_at = now()
                """, checkpoint.runId(), checkpoint.phase(), json(checkpoint));
    }

    @Override
    public Optional<ReactCheckpoint> loadCheckpoint(UUID runId) {
        return dsl.fetchOptional("""
                SELECT state
                FROM agent_run_checkpoint
                WHERE run_id = ? AND checkpoint_version = 2
                """, runId).map(record -> read(record.get("state", JSONB.class), ReactCheckpoint.class));
    }

    @Override
    public void saveStep(ReactStep step) {
        dsl.execute("""
                INSERT INTO agent_react_step
                    (id, run_id, step_number, status, action_summary, assistant_content, finish_reason,
                     provider_metadata, token_usage, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::timestamptz, ?::timestamptz)
                ON CONFLICT (id) DO UPDATE
                SET status = EXCLUDED.status,
                    action_summary = EXCLUDED.action_summary,
                    assistant_content = EXCLUDED.assistant_content,
                    finish_reason = EXCLUDED.finish_reason,
                    provider_metadata = EXCLUDED.provider_metadata,
                    token_usage = EXCLUDED.token_usage,
                    started_at = EXCLUDED.started_at,
                    completed_at = EXCLUDED.completed_at
                """, step.id(), step.runId(), step.stepNumber(), step.status().name(), step.actionSummary(),
                step.assistantContent(), step.finishReason(), json(step.providerMetadata()), json(step.tokenUsage()),
                step.startedAt(), step.completedAt());
    }

    @Override
    public void saveToolCall(ReactToolCall toolCall) {
        dsl.execute("""
                INSERT INTO agent_tool_call
                    (id, run_id, step_id, provider_call_id, call_index, tool_name, arguments, status,
                     result_output, result_data, error, result_count, latency_ms, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?, ?,
                        ?::timestamptz, ?::timestamptz)
                ON CONFLICT (id) DO UPDATE
                SET status = EXCLUDED.status,
                    arguments = EXCLUDED.arguments,
                    result_output = EXCLUDED.result_output,
                    result_data = EXCLUDED.result_data,
                    error = EXCLUDED.error,
                    result_count = EXCLUDED.result_count,
                    latency_ms = EXCLUDED.latency_ms,
                    started_at = COALESCE(agent_tool_call.started_at, EXCLUDED.started_at),
                    completed_at = EXCLUDED.completed_at
                """, toolCall.id(), toolCall.runId(), toolCall.stepId(), toolCall.providerCallId(),
                toolCall.callIndex(), toolCall.toolName(), json(toolCall.arguments()), toolCall.status().name(),
                toolCall.output(), json(toolCall.resultData()), json(toolCall.error()), toolCall.resultCount(),
                toolCall.latencyMs(), toolCall.startedAt(), toolCall.completedAt());
    }

    @Override
    public void saveKnowledgeReference(ReactKnowledgeReference reference) {
        var sources = reference.sources().stream().map(Enum::name).distinct().toArray(String[]::new);
        dsl.execute("""
                INSERT INTO agent_knowledge_reference
                    (id, run_id, tool_call_id, reference_key, knowledge_base_id, document_id,
                     document_version_id, chunk_id, document_title, excerpt, source_start, source_end,
                     first_source, sources, deep_read, score, metadata, first_deep_read_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        CASE WHEN ? THEN nextval('agent_knowledge_deep_read_sequence') ELSE NULL END)
                ON CONFLICT (run_id, reference_key) DO UPDATE
                SET tool_call_id = CASE
                        WHEN EXCLUDED.deep_read THEN EXCLUDED.tool_call_id
                        ELSE COALESCE(agent_knowledge_reference.tool_call_id, EXCLUDED.tool_call_id)
                    END,
                    document_version_id = CASE
                        WHEN EXCLUDED.deep_read THEN EXCLUDED.document_version_id
                        ELSE agent_knowledge_reference.document_version_id
                    END,
                    document_title = CASE
                        WHEN EXCLUDED.deep_read OR agent_knowledge_reference.document_title = ''
                            THEN EXCLUDED.document_title
                        ELSE agent_knowledge_reference.document_title
                    END,
                    excerpt = CASE
                        WHEN EXCLUDED.deep_read OR agent_knowledge_reference.excerpt = '' THEN EXCLUDED.excerpt
                        ELSE agent_knowledge_reference.excerpt
                    END,
                    source_start = COALESCE(EXCLUDED.source_start, agent_knowledge_reference.source_start),
                    source_end = COALESCE(EXCLUDED.source_end, agent_knowledge_reference.source_end),
                    sources = ARRAY(
                        SELECT DISTINCT value
                        FROM unnest(agent_knowledge_reference.sources || EXCLUDED.sources) AS value
                        ORDER BY value
                    ),
                    deep_read = agent_knowledge_reference.deep_read OR EXCLUDED.deep_read,
                    score = GREATEST(agent_knowledge_reference.score, EXCLUDED.score),
                    metadata = agent_knowledge_reference.metadata || EXCLUDED.metadata,
                    first_deep_read_order = COALESCE(
                        agent_knowledge_reference.first_deep_read_order,
                        EXCLUDED.first_deep_read_order
                    ),
                    updated_at = now()
                """, reference.id(), reference.runId(), reference.toolCallId(), reference.referenceKey(),
                reference.knowledgeBaseId(), reference.documentId(), reference.documentVersionId(),
                reference.chunkId(), reference.documentTitle(), reference.excerpt(), reference.sourceStart(),
                reference.sourceEnd(), reference.source().name(), sources, reference.deepRead(), reference.score(),
                json(reference.metadata()), reference.deepRead());
    }

    @Override
    public void prepareForRecovery(UUID runId) {
        dsl.transaction(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("""
                    UPDATE agent_tool_call
                    SET status = 'PENDING', started_at = NULL, completed_at = NULL, latency_ms = NULL
                    WHERE run_id = ? AND status = 'RUNNING'
                    """, runId);
            tx.execute("""
                    UPDATE agent_react_step
                    SET completed_at = NULL
                    WHERE run_id = ? AND status = 'RUNNING'
                    """, runId);
        });
    }

    @Override
    public Optional<ReactRunArtifacts> loadArtifacts(UUID runId) {
        var pipelineVersion = dsl.fetchOptional(
                "SELECT pipeline_version FROM rag_run WHERE id = ?", runId)
                .map(record -> record.get("pipeline_version", String.class));
        if (pipelineVersion.isEmpty() || !"agentic-react-v1".equals(pipelineVersion.get())) return Optional.empty();

        var checkpoint = loadCheckpoint(runId).orElse(null);
        var steps = loadSteps(runId);
        var toolCalls = loadToolCalls(runId);
        var references = loadKnowledgeReferences(runId);
        var ranking = loadDocumentRanking(runId);
        return Optional.of(new ReactRunArtifacts(
                runId,
                ReactCheckpoint.CURRENT_VERSION,
                checkpoint,
                steps,
                toolCalls,
                references,
                ranking,
                checkpoint == null ? Map.of() : checkpoint.budget()
        ));
    }

    @Override
    public List<ReactRankedDocument> loadDocumentRanking(UUID runId) {
        return dsl.fetch("""
                WITH documents AS (
                    SELECT reference.document_id,
                           (array_agg(reference.document_version_id ORDER BY reference.deep_read DESC,
                                      reference.first_deep_read_order NULLS LAST,
                                      reference.first_discovery_order))[1] AS document_version_id,
                           (array_agg(reference.document_title ORDER BY reference.deep_read DESC,
                                      reference.first_deep_read_order NULLS LAST,
                                      reference.first_discovery_order))[1] AS document_title,
                           bool_or(reference.deep_read) AS deep_read,
                           min(reference.first_discovery_order) AS first_discovery_order,
                           min(reference.first_deep_read_order) AS first_deep_read_order,
                           max(reference.score) AS best_score,
                           ARRAY(
                               SELECT DISTINCT source
                               FROM agent_knowledge_reference nested,
                                    unnest(nested.sources) AS source
                               WHERE nested.run_id = reference.run_id
                                 AND nested.document_id = reference.document_id
                               ORDER BY source
                           ) AS sources,
                           array_agg(DISTINCT reference.chunk_id)
                               FILTER (WHERE reference.chunk_id IS NOT NULL) AS chunk_ids
                    FROM agent_knowledge_reference reference
                    WHERE reference.run_id = ?
                    GROUP BY reference.run_id, reference.document_id
                ), ranked AS (
                    SELECT row_number() OVER (
                               ORDER BY deep_read DESC,
                                        first_deep_read_order NULLS LAST,
                                        first_discovery_order,
                                        document_id
                           ) AS rank_number,
                           *
                    FROM documents
                )
                SELECT * FROM ranked ORDER BY rank_number
                """, runId).map(record -> new ReactRankedDocument(
                Math.toIntExact(record.get("rank_number", Long.class)),
                record.get("document_id", UUID.class),
                record.get("document_version_id", UUID.class),
                record.get("document_title", String.class),
                Boolean.TRUE.equals(record.get("deep_read", Boolean.class)),
                record.get("first_discovery_order", Long.class),
                record.get("first_deep_read_order", Long.class),
                record.get("best_score", Double.class),
                enumSources(record.get("sources", String[].class)),
                uuids(record.get("chunk_ids", UUID[].class))
        ));
    }

    private List<ReactStep> loadSteps(UUID runId) {
        return dsl.fetch("""
                SELECT id, run_id, step_number, status, action_summary, assistant_content, finish_reason,
                       provider_metadata, token_usage, started_at, completed_at
                FROM agent_react_step
                WHERE run_id = ?
                ORDER BY step_number
                """, runId).map(record -> new ReactStep(
                record.get("id", UUID.class), record.get("run_id", UUID.class),
                record.get("step_number", Integer.class),
                ReactStepStatus.valueOf(record.get("status", String.class)),
                record.get("action_summary", String.class), record.get("assistant_content", String.class),
                record.get("finish_reason", String.class), map(record.get("provider_metadata", JSONB.class)),
                map(record.get("token_usage", JSONB.class)), instant(record, "started_at"),
                nullableInstant(record, "completed_at")
        ));
    }

    private List<ReactToolCall> loadToolCalls(UUID runId) {
        return dsl.fetch("""
                SELECT id, run_id, step_id, provider_call_id, call_index, tool_name, arguments, status,
                       result_output, result_data, error, result_count, latency_ms, started_at, completed_at
                FROM agent_tool_call
                WHERE run_id = ?
                ORDER BY step_id, call_index
                """, runId).map(record -> new ReactToolCall(
                record.get("id", UUID.class), record.get("run_id", UUID.class),
                record.get("step_id", UUID.class), record.get("provider_call_id", String.class),
                record.get("call_index", Integer.class), record.get("tool_name", String.class),
                map(record.get("arguments", JSONB.class)),
                ReactToolCallStatus.valueOf(record.get("status", String.class)),
                record.get("result_output", String.class), map(record.get("result_data", JSONB.class)),
                map(record.get("error", JSONB.class)), record.get("result_count", Integer.class),
                record.get("latency_ms", Long.class), nullableInstant(record, "started_at"),
                nullableInstant(record, "completed_at")
        ));
    }

    private List<ReactKnowledgeReference> loadKnowledgeReferences(UUID runId) {
        return dsl.fetch("""
                SELECT id, run_id, tool_call_id, knowledge_base_id, document_id, document_version_id,
                       chunk_id, document_title, excerpt, source_start, source_end, first_source, sources,
                       deep_read, score, metadata, first_discovery_order, first_deep_read_order,
                       created_at, updated_at
                FROM agent_knowledge_reference
                WHERE run_id = ?
                ORDER BY first_discovery_order
                """, runId).map(record -> new ReactKnowledgeReference(
                record.get("id", UUID.class), record.get("run_id", UUID.class),
                record.get("tool_call_id", UUID.class), record.get("knowledge_base_id", UUID.class),
                record.get("document_id", UUID.class), record.get("document_version_id", UUID.class),
                record.get("chunk_id", UUID.class), record.get("document_title", String.class),
                record.get("excerpt", String.class), record.get("source_start", Integer.class),
                record.get("source_end", Integer.class),
                KnowledgeReferenceSource.valueOf(record.get("first_source", String.class)),
                enumSources(record.get("sources", String[].class)),
                Boolean.TRUE.equals(record.get("deep_read", Boolean.class)), record.get("score", Double.class),
                map(record.get("metadata", JSONB.class)), record.get("first_discovery_order", Long.class),
                record.get("first_deep_read_order", Long.class), instant(record, "created_at"),
                instant(record, "updated_at")
        ));
    }

    private List<KnowledgeReferenceSource> enumSources(String[] values) {
        if (values == null) return List.of();
        return Arrays.stream(values).map(KnowledgeReferenceSource::valueOf).toList();
    }

    private List<UUID> uuids(UUID[] values) {
        return values == null ? List.of() : List.of(values);
    }

    private Map<String, Object> map(JSONB value) {
        return value == null ? Map.of() : read(value, MAP_TYPE);
    }

    private Instant instant(Record record, String field) {
        return record.get(field, OffsetDateTime.class).toInstant();
    }

    private Instant nullableInstant(Record record, String field) {
        var value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize ReAct persistence payload", exception);
        }
    }

    private <T> T read(JSONB value, Class<T> type) {
        try {
            return objectMapper.readValue(value.data(), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted ReAct payload is invalid", exception);
        }
    }

    private <T> T read(JSONB value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value.data(), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted ReAct payload is invalid", exception);
        }
    }
}
