package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.agent.v7.AgenticV7Limits;
import com.yanyue.rag.domain.agent.v8.AgenticV8Limits;
import com.yanyue.rag.domain.model.PipelineConfig;
import com.yanyue.rag.domain.port.RunRecordPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqRunRecordAdapter implements RunRecordPort {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqRunRecordAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public void create(UUID runId, UUID organizationId, UUID userId, UUID conversationId, CreateRunRequest request) {
        var conversationOwned = dsl.fetchExists(dsl.selectOne().from("conversation")
                .where(org.jooq.impl.DSL.field("id").eq(conversationId))
                .and(org.jooq.impl.DSL.field("organization_id").eq(organizationId)));
        if (!conversationOwned) throw new IllegalArgumentException("Conversation not found");
        dsl.transaction(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var turnId = UUID.randomUUID();
            tx.execute("""
                    INSERT INTO conversation_turn (id, conversation_id)
                    VALUES (?, ?)
                    """, turnId, conversationId);
            tx.execute("""
                    INSERT INTO rag_run
                        (id, conversation_id, organization_id, requested_mode, query_text, scope, filters,
                         model_profile_id, status, pipeline_version, prompt_version, created_by, turn_id)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, 'QUEUED', 'rag-pipeline-v1', 'answer-v1', ?, ?)
                    """, runId, conversationId, organizationId, request.mode().name(), request.query(),
                    json(request.scope()), json(request.filters()), request.modelProfileId(), userId, turnId);
            tx.execute("""
                    INSERT INTO conversation_message (conversation_id, role, content, metadata, run_id, turn_id)
                    VALUES (?, 'user', ?, jsonb_build_object('runId', ?::text), ?, ?)
                    """, conversationId, request.query().strip(), runId, runId, turnId);
            tx.execute("""
                    UPDATE conversation_turn SET active_run_id = ?, updated_at = now() WHERE id = ?
                    """, runId, turnId);
            tx.execute("UPDATE conversation SET updated_at = now() WHERE id = ?", conversationId);
        });
    }

    @Override
    public ReprocessSeed prepareReprocess(
            UUID sourceRunId,
            UUID newRunId,
            UUID organizationId,
            UUID userId
    ) {
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var source = tx.fetchOptional("""
                    SELECT run.*, turn.active_run_id
                    FROM rag_run run
                    JOIN conversation_turn turn ON turn.id = run.turn_id
                    WHERE run.id = ? AND run.organization_id = ? AND run.created_by = ?
                      AND run.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                    FOR UPDATE OF run, turn
                    """, sourceRunId, organizationId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("当前回答不能重新处理"));
            if (!sourceRunId.equals(source.get("active_run_id", UUID.class))) {
                throw new IllegalArgumentException("该回答已被更新，请刷新会话后重试");
            }
            var conversationId = source.get("conversation_id", UUID.class);
            var turnId = source.get("turn_id", UUID.class);
            var query = source.get("query_text", String.class);
            var requestedMode = RunMode.valueOf(source.get("requested_mode", String.class));
            var scopeJson = source.get("scope", org.jooq.JSONB.class).data();
            var filtersJson = source.get("filters", org.jooq.JSONB.class).data();
            var scope = read(scopeJson, KnowledgeScope.class);
            var filters = read(filtersJson, new TypeReference<java.util.List<MetadataFilter>>() { });
            var modelProfileId = source.get("model_profile_id", UUID.class);

            tx.execute("""
                    INSERT INTO rag_run
                        (id, conversation_id, organization_id, requested_mode, query_text, scope, filters,
                         model_profile_id, status, pipeline_version, prompt_version, created_by, turn_id,
                         reprocessed_from_run_id, pipeline_config_id, query_rewrite_profile_id,
                         rerank_profile_id, assistant_profile_version_id, runtime_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            jsonb_build_object('reprocessedFromRunId', ?::text))
                    """, newRunId, conversationId, organizationId, requestedMode.name(), query,
                    scopeJson, filtersJson, modelProfileId,
                    source.get("pipeline_version", String.class), source.get("prompt_version", String.class),
                    userId, turnId, sourceRunId, source.get("pipeline_config_id", UUID.class),
                    source.get("query_rewrite_profile_id", UUID.class), source.get("rerank_profile_id", UUID.class),
                    source.get("assistant_profile_version_id", UUID.class), sourceRunId);
            tx.execute("""
                    INSERT INTO conversation_message (conversation_id, role, content, metadata, run_id, turn_id)
                    VALUES (?, 'user', ?, jsonb_build_object('runId', ?::text), ?, ?)
                    ON CONFLICT (turn_id, role)
                        WHERE turn_id IS NOT NULL AND role = 'user'
                    DO NOTHING
                    """, conversationId, query, newRunId, newRunId, turnId);
            tx.execute("""
                    UPDATE conversation_turn
                    SET active_run_id = ?, updated_at = now()
                    WHERE id = ? AND active_run_id = ?
                    """, newRunId, turnId, sourceRunId);
            tx.execute("UPDATE conversation SET updated_at = now() WHERE id = ?", conversationId);
            return new ReprocessSeed(conversationId,
                    new CreateRunRequest(query, requestedMode, scope, filters, modelProfileId));
        });
    }

    @Override
    public Optional<UUID> pipelineConfigId(UUID runId) {
        return dsl.fetchOptional("SELECT pipeline_config_id FROM rag_run WHERE id = ?", runId)
                .map(record -> record.get("pipeline_config_id", UUID.class));
    }

    @Override
    public void markRouting(UUID runId) {
        dsl.execute("""
                UPDATE rag_run
                SET status = 'RUNNING', started_at = COALESCE(started_at, now())
                WHERE id = ? AND status = 'QUEUED' AND cancellation_requested = false
                """, runId);
    }

    @Override
    public void markRunning(UUID runId, RunMode selectedMode) {
        dsl.execute("""
                UPDATE rag_run
                SET status = 'RUNNING', selected_mode = ?, started_at = COALESCE(started_at, now())
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING') AND cancellation_requested = false
                """,
                selectedMode.name(), runId);
    }

    @Override
    public void applyRuntime(UUID runId, PipelineConfig config, UUID chatProfileId) {
        var snapshot = java.util.Map.<String, Object>ofEntries(
                java.util.Map.entry("pipelineConfigId", config.id()),
                java.util.Map.entry("pipelineVersion", config.pipelineVersion()),
                java.util.Map.entry("promptVersion", config.promptVersion()),
                java.util.Map.entry("chatProfileId", chatProfileId),
                java.util.Map.entry("chatModel", profileSnapshot(config.organizationId(), chatProfileId)),
                java.util.Map.entry("queryRewriteProfileId", config.queryRewriteProfileId()),
                java.util.Map.entry("queryRewriteModel", profileSnapshot(config.organizationId(), config.queryRewriteProfileId())),
                java.util.Map.entry("rerankProfileId", config.rerankProfileId()),
                java.util.Map.entry("rerankModel", profileSnapshot(config.organizationId(), config.rerankProfileId())),
                java.util.Map.entry("keywordTopK", config.keywordTopK()),
                java.util.Map.entry("semanticTopK", config.semanticTopK()),
                java.util.Map.entry("rrfCandidateLimit", config.rrfCandidateLimit()),
                java.util.Map.entry("rerankCandidateLimit", config.rerankCandidateLimit()),
                java.util.Map.entry("finalContextGroups", config.finalContextGroups()),
                java.util.Map.entry("contextTokenBudget", config.contextTokenBudget()),
                java.util.Map.entry("minimumRerankScore", config.minimumRerankScore())
        );
        dsl.execute("""
                UPDATE rag_run
                SET pipeline_config_id = ?, model_profile_id = ?, query_rewrite_profile_id = ?,
                    rerank_profile_id = ?, pipeline_version = ?, prompt_version = ?, runtime_snapshot = ?::jsonb
                WHERE id = ?
                """, config.id(), chatProfileId, config.queryRewriteProfileId(), config.rerankProfileId(),
                config.pipelineVersion(), config.promptVersion(), json(snapshot), runId);
    }

    @Override
    public void applyAgentRuntime(UUID runId, PipelineConfig config, UUID chatProfileId) {
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        snapshot.put("pipelineConfigId", config.id());
        snapshot.put("pipelineVersion", "agentic-react-v1");
        snapshot.put("promptVersion", "weknora-progressive-rag-v1");
        snapshot.put("chatProfileId", chatProfileId);
        snapshot.put("chatModel", profileSnapshot(config.organizationId(), chatProfileId));
        snapshot.put("rerankProfileId", config.rerankProfileId());
        snapshot.put("rerankModel", profileSnapshot(config.organizationId(), config.rerankProfileId()));
        snapshot.put("keywordTopK", config.keywordTopK());
        snapshot.put("semanticTopK", config.semanticTopK());
        snapshot.put("rrfCandidateLimit", config.rrfCandidateLimit());
        snapshot.put("rerankCandidateLimit", config.rerankCandidateLimit());
        snapshot.put("maxIterations", config.maxIterations());
        snapshot.put("maxRetrievalRounds", config.maxRetrievalRounds());
        snapshot.put("maxSubQueries", config.maxSubQueries());
        snapshot.put("maxSearchCalls", config.maxSearchCalls());
        snapshot.put("maxDeepReadCalls", config.maxDeepReadCalls());
        snapshot.put("maxToolCallsPerRound", config.maxToolCallsPerRound());
        snapshot.put("maxFinalReferences", config.maxFinalReferences());
        snapshot.put("recentTurns", config.recentTurns());
        snapshot.put("maxContextTokens", config.maxContextTokens());
        snapshot.put("llmTimeoutSeconds", config.llmTimeoutSeconds());
        snapshot.put("agenticLoopTimeoutSeconds", config.agenticLoopTimeoutSeconds());
        snapshot.put("toolTimeoutSeconds", config.toolTimeoutSeconds());
        snapshot.put("maxCompletionTokens", config.maxCompletionTokens());
        snapshot.put("temperature", config.temperature());
        snapshot.put("parallelToolCalls", config.parallelToolCalls());
        snapshot.put("requireDeepReadBeforeAnswer", config.requireDeepReadBeforeAnswer());
        dsl.execute("""
                UPDATE rag_run
                SET pipeline_config_id = ?, model_profile_id = ?, rerank_profile_id = ?,
                    pipeline_version = 'agentic-react-v1', prompt_version = 'weknora-progressive-rag-v1',
                    runtime_snapshot = ?::jsonb
                WHERE id = ?
                """, config.id(), chatProfileId, config.rerankProfileId(), json(snapshot), runId);
    }

    @Override
    public void applyAgentHybridRuntime(UUID runId, PipelineConfig config, UUID chatProfileId) {
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        snapshot.put("pipelineConfigId", config.id());
        snapshot.put("pipelineVersion", "agentic-hybrid-v2");
        snapshot.put("promptVersion", "rewrite-v1+planner-v3+evidence-v2+coverage-v3+gap-v3");
        snapshot.put("chatProfileId", chatProfileId);
        snapshot.put("chatModel", profileSnapshot(config.organizationId(), chatProfileId));
        snapshot.put("queryRewriteProfileId", config.queryRewriteProfileId());
        snapshot.put("queryRewriteModel", profileSnapshot(config.organizationId(), config.queryRewriteProfileId()));
        snapshot.put("rerankProfileId", config.rerankProfileId());
        snapshot.put("rerankModel", profileSnapshot(config.organizationId(), config.rerankProfileId()));
        snapshot.put("keywordTopK", config.keywordTopK());
        snapshot.put("semanticTopK", config.semanticTopK());
        snapshot.put("rrfCandidateLimit", config.rrfCandidateLimit());
        snapshot.put("rerankCandidateLimit", config.rerankCandidateLimit());
        snapshot.put("minimumRerankScore", config.minimumRerankScore());
        snapshot.put("maxRetrievalRounds", config.maxRetrievalRounds());
        snapshot.put("maxSubQueries", Math.min(6, config.maxSubQueries()));
        snapshot.put("maxSearchCalls", config.maxSearchCalls());
        snapshot.put("maxDeepReadCalls", config.maxDeepReadCalls());
        snapshot.put("maxParallelism", 4);
        snapshot.put("maxFinalReferences", config.maxFinalReferences());
        snapshot.put("recentTurns", config.recentTurns());
        snapshot.put("llmTimeoutSeconds", config.llmTimeoutSeconds());
        snapshot.put("agenticLoopTimeoutSeconds", config.agenticLoopTimeoutSeconds());
        snapshot.put("retrievalLoopTimeoutSeconds", config.agenticLoopTimeoutSeconds());
        snapshot.put("fastTimeoutSeconds", config.fastTimeoutSeconds());
        dsl.execute("""
                UPDATE rag_run
                SET pipeline_config_id = ?, model_profile_id = ?, query_rewrite_profile_id = ?,
                    rerank_profile_id = ?, pipeline_version = 'agentic-hybrid-v2',
                    prompt_version = 'rewrite-v1+planner-v3+evidence-v2+coverage-v3+gap-v3', runtime_snapshot = ?::jsonb
                WHERE id = ?
                """, config.id(), chatProfileId, config.queryRewriteProfileId(), config.rerankProfileId(),
                json(snapshot), runId);
    }

    @Override
    public void applyAgentV4Runtime(UUID runId, PipelineConfig config, UUID chatProfileId) {
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        snapshot.put("pipelineConfigId", config.id());
        snapshot.put("pipelineVersion", "agentic-rag-v4");
        snapshot.put("checkpointVersion", 3);
        snapshot.put("promptVersion", "agentic-v4-request-analysis+deep-read+evidence-judge+answer-v2");
        snapshot.put("chatProfileId", chatProfileId);
        snapshot.put("chatModel", profileSnapshot(config.organizationId(), chatProfileId));
        snapshot.put("rerankProfileId", config.rerankProfileId());
        snapshot.put("rerankModel", profileSnapshot(config.organizationId(), config.rerankProfileId()));
        snapshot.put("maximumGoals", 3);
        snapshot.put("maximumRepairRounds", 1);
        snapshot.put("maximumPhysicalSearches", 9);
        snapshot.put("maximumDeepReadCalls", 6);
        snapshot.put("maximumJudgeCalls", 1);
        snapshot.put("maximumGenerativeCalls", 9);
        snapshot.put("maximumModelAttempts", 12);
        snapshot.put("deadlineSeconds", Math.min(120, Math.max(90, config.agenticLoopTimeoutSeconds())));
        snapshot.put("keywordTopK", Math.min(12, config.keywordTopK()));
        snapshot.put("semanticTopK", Math.min(12, config.semanticTopK()));
        snapshot.put("maxFinalReferences", Math.min(8, config.maxFinalReferences()));
        dsl.execute("""
                UPDATE rag_run
                SET pipeline_config_id = ?, model_profile_id = ?, rerank_profile_id = ?,
                    pipeline_version = 'agentic-rag-v4',
                    prompt_version = 'agentic-v4-request-analysis+deep-read+evidence-judge+answer-v2',
                    runtime_snapshot = ?::jsonb
                WHERE id = ?
                """, config.id(), chatProfileId, config.rerankProfileId(), json(snapshot), runId);
    }

    @Override
    public void applyAgentV5Runtime(
            UUID runId,
            PipelineConfig config,
            UUID chatProfileId,
            java.util.Map<String, Object> effectiveLimits
    ) {
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        snapshot.put("pipelineConfigId", config.id());
        snapshot.put("pipelineVersion", "agentic-rag-v5");
        snapshot.put("checkpointVersion", 4);
        snapshot.put("effectiveLimitsVersion", 1);
        snapshot.put("promptVersion", "agentic-v5-request-analysis+deep-read+evidence-judge+answer-v2");
        snapshot.put("chatProfileId", chatProfileId);
        snapshot.put("chatModel", profileSnapshot(config.organizationId(), chatProfileId));
        snapshot.put("rerankProfileId", config.rerankProfileId());
        snapshot.put("rerankModel", profileSnapshot(config.organizationId(), config.rerankProfileId()));
        snapshot.putAll(java.util.Map.copyOf(effectiveLimits));
        dsl.execute("""
                UPDATE rag_run
                SET pipeline_config_id = ?, model_profile_id = ?, rerank_profile_id = ?,
                    pipeline_version = 'agentic-rag-v5',
                    prompt_version = 'agentic-v5-request-analysis+deep-read+evidence-judge+answer-v2',
                    runtime_snapshot = ?::jsonb
                WHERE id = ?
                """, config.id(), chatProfileId, config.rerankProfileId(), json(snapshot), runId);
    }

    @Override
    public void applyAgentV7Runtime(
            UUID runId,
            PipelineConfig config,
            UUID chatProfileId,
            java.util.Map<String, Object> effectiveLimits
    ) {
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        snapshot.put("pipelineConfigId", config.id());
        snapshot.put("pipelineVersion", "agentic-rag-v7");
        // v7 currently reuses the existing agent checkpoint storage schema. Keep
        // the runtime snapshot consistent with the adapter until a dedicated v7
        // recovery adapter and migration are introduced.
        snapshot.put("checkpointVersion", 3);
        snapshot.put("rankingSchemaVersion", 3);
        snapshot.put("rankingPolicy", "EVIDENCE_BOOLEAN_GOAL_BALANCED_V2");
        snapshot.put("coverageSchemaVersion", 4);
        snapshot.put("effectiveLimitsVersion", 3);
        snapshot.put("limitsVersion", AgenticV7Limits.VERSION);
        snapshot.put("promptVersion", "agentic-v7-request-analysis-v1+deep-read+evidence-judge+answer-v6");
        snapshot.put("chatProfileId", chatProfileId);
        snapshot.put("chatModel", profileSnapshot(config.organizationId(), chatProfileId));
        snapshot.put("rerankProfileId", config.rerankProfileId());
        snapshot.put("rerankModel", profileSnapshot(config.organizationId(), config.rerankProfileId()));
        snapshot.putAll(java.util.Map.copyOf(effectiveLimits));
        dsl.execute("""
                UPDATE rag_run
                SET pipeline_config_id = ?, model_profile_id = ?, rerank_profile_id = ?,
                    pipeline_version = 'agentic-rag-v7',
                    prompt_version = 'agentic-v7-request-analysis-v1+deep-read+evidence-judge+answer-v6',
                    runtime_snapshot = ?::jsonb
                WHERE id = ?
                """, config.id(), chatProfileId, config.rerankProfileId(), json(snapshot), runId);
    }

    @Override
    public void applyAgentV8Runtime(
            UUID runId,
            PipelineConfig config,
            UUID chatProfileId,
            java.util.Map<String, Object> effectiveLimits,
            String promptVersion
    ) {
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        snapshot.put("pipelineConfigId", config.id());
        snapshot.put("pipelineVersion", "agentic-rag-v8");
        snapshot.put("checkpointVersion", 3);
        snapshot.put("rankingSchemaVersion", 3);
        snapshot.put("rankingPolicy", "EVIDENCE_BOOLEAN_GOAL_BALANCED_V2");
        snapshot.put("coverageSchemaVersion", 4);
        snapshot.put("effectiveLimitsVersion", 1);
        snapshot.put("limitsVersion", AgenticV8Limits.VERSION);
        snapshot.put("promptVersion", promptVersion);
        snapshot.put("chatProfileId", chatProfileId);
        snapshot.put("chatModel", profileSnapshot(config.organizationId(), chatProfileId));
        snapshot.put("rerankProfileId", config.rerankProfileId());
        snapshot.put("rerankModel", profileSnapshot(config.organizationId(), config.rerankProfileId()));
        snapshot.putAll(java.util.Map.copyOf(effectiveLimits));
        dsl.execute("""
                UPDATE rag_run
                SET pipeline_config_id = ?, model_profile_id = ?, rerank_profile_id = ?,
                    pipeline_version = 'agentic-rag-v8',
                    prompt_version = ?,
                    runtime_snapshot = ?::jsonb
                WHERE id = ?
                """, config.id(), chatProfileId, config.rerankProfileId(), promptVersion,
                json(snapshot), runId);
    }

    @Override
    public void markNoAnswer(UUID runId, String reason) {
        dsl.execute("""
                UPDATE rag_run SET no_answer_reason = ?
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING') AND cancellation_requested = false
                """, reason, runId);
    }

    @Override
    public void markAnswerMode(UUID runId, String answerMode, String stopReason) {
        dsl.execute("""
                UPDATE rag_run SET answer_mode = ?, stop_reason = ?
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING') AND cancellation_requested = false
                """, answerMode, stopReason, runId);
    }

    @Override
    public void markRetrievalHealth(UUID runId, String retrievalHealth, int evidenceCount) {
        dsl.execute("""
                UPDATE rag_run SET retrieval_health = ?, evidence_count = ?
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING') AND cancellation_requested = false
                """, retrievalHealth, Math.max(0, evidenceCount), runId);
    }

    @Override
    public void applyAssistantProfile(UUID runId, UUID assistantProfileVersionId) {
        dsl.execute("""
                UPDATE rag_run SET assistant_profile_version_id = ?,
                    runtime_snapshot = runtime_snapshot || jsonb_build_object(
                        'assistantProfileVersionId', ?::text,
                        'assistantProfileVersion', (
                            SELECT version FROM assistant_profile_version WHERE id = ?
                        )
                    )
                WHERE id = ?
                """, assistantProfileVersionId, assistantProfileVersionId, assistantProfileVersionId, runId);
    }

    @Override
    public boolean isCancellationRequested(UUID runId) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                "SELECT cancellation_requested FROM rag_run WHERE id = ?", runId, Boolean.class));
    }

    @Override
    public void complete(UUID runId) {
        dsl.execute("""
                UPDATE rag_run
                SET status = 'COMPLETED', completed_at = now()
                WHERE id = ? AND status = 'RUNNING' AND cancellation_requested = false
                """, runId);
    }

    @Override
    public void fail(UUID runId, String message) {
        var changed = dsl.execute("""
                UPDATE rag_run
                SET status = 'FAILED', error_message = ?, completed_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING') AND cancellation_requested = false
                """,
                message, runId);
        if (changed > 0) upsertTerminalMessage(runId, "暂时无法完成本次回答，请重新处理。");
    }

    @Override
    public void fail(UUID runId, String message, String stopReason) {
        var changed = dsl.execute("""
                UPDATE rag_run
                SET status = 'FAILED', error_message = ?, stop_reason = ?, completed_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING') AND cancellation_requested = false
                """, message, stopReason, runId);
        if (changed > 0) upsertTerminalMessage(runId, "暂时无法完成本次回答，请重新处理。");
    }

    @Override
    public void cancel(UUID runId) {
        var changed = dsl.execute("""
                UPDATE rag_run
                SET cancellation_requested = true, status = 'CANCELLED', stop_reason = 'CANCELLED', completed_at = now()
                WHERE id = ? AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """, runId);
        if (changed > 0) upsertTerminalMessage(runId, "本次处理已停止。");
    }

    private void upsertTerminalMessage(UUID runId, String content) {
        dsl.execute("""
                INSERT INTO conversation_message
                    (conversation_id, role, content, metadata, run_id, turn_id)
                SELECT conversation_id, 'assistant', ?, jsonb_build_object('runId', id::text), id, turn_id
                FROM rag_run
                WHERE id = ? AND conversation_id IS NOT NULL AND turn_id IS NOT NULL
                ON CONFLICT (run_id, role)
                    WHERE run_id IS NOT NULL AND role IN ('user', 'assistant')
                DO UPDATE SET content = EXCLUDED.content, turn_id = EXCLUDED.turn_id
                """, content, runId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize run request", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Run request snapshot is invalid", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Run request snapshot is invalid", exception);
        }
    }

    private Map<String, Object> profileSnapshot(UUID organizationId, UUID profileId) {
        if (profileId == null) return Map.of();
        return dsl.fetchOptional("""
                SELECT profile_type, provider, name, model_name, settings::text AS settings, updated_at
                FROM model_profile WHERE id = ? AND organization_id = ?
                """, profileId, organizationId).map(record -> {
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("id", profileId);
            snapshot.put("profileType", record.get("profile_type", String.class));
            snapshot.put("provider", record.get("provider", String.class));
            snapshot.put("name", record.get("name", String.class));
            snapshot.put("modelName", record.get("model_name", String.class));
            var updatedAt = record.get("updated_at", OffsetDateTime.class);
            if (updatedAt != null) snapshot.put("revision", updatedAt.toInstant().toString());
            snapshot.put("settingsSha256", sha256(record.get("settings", String.class)));
            return Map.copyOf(snapshot);
        }).orElseGet(Map::of);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "{}" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
