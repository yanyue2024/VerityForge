package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.model.PipelineConfig;
import com.yanyue.rag.domain.port.PipelineConfigRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqPipelineConfigRepository implements PipelineConfigRepository {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqPipelineConfigRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PipelineConfig> findActive(UUID organizationId) {
        return dsl.fetchOptional("""
                SELECT * FROM pipeline_config
                WHERE organization_id = ? AND active = true
                ORDER BY created_at DESC LIMIT 1
                """, organizationId).map(this::map);
    }

    @Override
    public Optional<PipelineConfig> findDraft(UUID organizationId) {
        return dsl.fetchOptional("""
                SELECT * FROM pipeline_config
                WHERE organization_id = ? AND lifecycle_status = 'DRAFT'
                ORDER BY created_at DESC LIMIT 1
                """, organizationId).map(this::map);
    }

    @Override
    public Optional<PipelineConfig> findById(UUID organizationId, UUID configId) {
        return dsl.fetchOptional("SELECT * FROM pipeline_config WHERE organization_id = ? AND id = ?",
                organizationId, configId).map(this::map);
    }

    @Override
    public java.util.List<PipelineConfig> findVersions(UUID organizationId) {
        return dsl.fetch("""
                SELECT * FROM pipeline_config WHERE organization_id = ?
                ORDER BY created_at DESC
                """, organizationId).map(this::map);
    }

    @Override
    public PipelineConfig activate(PipelineConfig config) {
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("UPDATE pipeline_config SET active = false, lifecycle_status = 'ARCHIVED', updated_at = now() WHERE organization_id = ? AND active = true",
                    config.organizationId());
            tx.execute("""
                    UPDATE pipeline_config
                    SET chat_profile_id = ?, query_rewrite_profile_id = ?, previewed_at = null, updated_at = now()
                    WHERE organization_id = ? AND lifecycle_status = 'DRAFT'
                    """, config.chatProfileId(), config.queryRewriteProfileId(), config.organizationId());
            var settings = Map.<String, Object>ofEntries(
                    Map.entry("keywordTopK", config.keywordTopK()),
                    Map.entry("semanticTopK", config.semanticTopK()),
                    Map.entry("rrfCandidateLimit", config.rrfCandidateLimit()),
                    Map.entry("rerankCandidateLimit", config.rerankCandidateLimit()),
                    Map.entry("finalContextGroups", config.finalContextGroups()),
                    Map.entry("contextTokenBudget", config.contextTokenBudget()),
                    Map.entry("minimumRerankScore", config.minimumRerankScore()),
                    Map.entry("fastTimeoutSeconds", config.fastTimeoutSeconds()),
                    Map.entry("maxIterations", config.maxIterations()),
                    Map.entry("maxRetrievalRounds", config.maxRetrievalRounds()),
                    Map.entry("maxSubQueries", config.maxSubQueries()),
                    Map.entry("maxSearchCalls", config.maxSearchCalls()),
                    Map.entry("maxDeepReadCalls", config.maxDeepReadCalls()),
                    Map.entry("maxToolCallsPerRound", config.maxToolCallsPerRound()),
                    Map.entry("maxFinalReferences", config.maxFinalReferences()),
                    Map.entry("recentTurns", config.recentTurns()),
                    Map.entry("maxContextTokens", config.maxContextTokens()),
                    Map.entry("llmTimeoutSeconds", config.llmTimeoutSeconds()),
                    Map.entry("agenticLoopTimeoutSeconds", config.agenticLoopTimeoutSeconds()),
                    Map.entry("toolTimeoutSeconds", config.toolTimeoutSeconds()),
                    Map.entry("maxCompletionTokens", config.maxCompletionTokens()),
                    Map.entry("temperature", config.temperature()),
                    Map.entry("parallelToolCalls", config.parallelToolCalls()),
                    Map.entry("requireDeepReadBeforeAnswer", config.requireDeepReadBeforeAnswer())
            );
            var record = tx.fetchOne("""
                    INSERT INTO pipeline_config
                        (id, organization_id, name, pipeline_version, parser_version, chunk_policy_version,
                         embedding_model_version, prompt_version, settings, active, chat_profile_id,
                         query_rewrite_profile_id, rerank_profile_id, lifecycle_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'normalized-document-v1', 'adaptive-parent-child-v1',
                            'generation-controlled', ?, ?::jsonb, true, ?, ?, ?, 'PUBLISHED', ?::timestamptz, ?::timestamptz)
                    RETURNING *
                    """, config.id(), config.organizationId(), config.name(), config.pipelineVersion(),
                    config.promptVersion(), json(settings), config.chatProfileId(), config.queryRewriteProfileId(),
                    config.rerankProfileId(), OffsetDateTime.ofInstant(config.createdAt(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(config.updatedAt(), ZoneOffset.UTC));
            return map(record);
        });
    }

    @Override
    public PipelineConfig saveDraft(PipelineConfig config) {
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("DELETE FROM pipeline_config WHERE organization_id = ? AND lifecycle_status = 'DRAFT'",
                    config.organizationId());
            var settings = settingsMap(config);
            var record = tx.fetchOne("""
                    INSERT INTO pipeline_config
                        (id, organization_id, name, pipeline_version, parser_version, chunk_policy_version,
                         embedding_model_version, prompt_version, settings, active, chat_profile_id,
                         query_rewrite_profile_id, rerank_profile_id, lifecycle_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'normalized-document-v1', 'adaptive-parent-child-v1',
                            'generation-controlled', ?, ?::jsonb, false, ?, ?, ?, 'DRAFT',
                            ?::timestamptz, ?::timestamptz) RETURNING *
                    """, config.id(), config.organizationId(), config.name(), config.pipelineVersion(),
                    config.promptVersion(), json(settings), config.chatProfileId(), config.queryRewriteProfileId(),
                    config.rerankProfileId(), OffsetDateTime.ofInstant(config.createdAt(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(config.updatedAt(), ZoneOffset.UTC));
            return map(record);
        });
    }

    @Override
    public PipelineConfig publishDraft(UUID organizationId, UUID configId) {
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("""
                    UPDATE pipeline_config SET active = false, lifecycle_status = 'ARCHIVED', updated_at = now()
                    WHERE organization_id = ? AND active = true
                    """, organizationId);
            var record = tx.fetchOne("""
                    UPDATE pipeline_config SET active = true, lifecycle_status = 'PUBLISHED', updated_at = now()
                    WHERE organization_id = ? AND id = ? AND lifecycle_status = 'DRAFT' RETURNING *
                    """, organizationId, configId);
            if (record == null) throw new IllegalArgumentException("Pipeline draft was not found");
            return map(record);
        });
    }

    @Override
    public void markDraftPreviewed(UUID organizationId, UUID configId) {
        dsl.execute("""
                UPDATE pipeline_config SET previewed_at = now(), updated_at = now()
                WHERE organization_id = ? AND id = ? AND lifecycle_status = 'DRAFT'
                """, organizationId, configId);
    }

    @Override
    public boolean isDraftPreviewed(UUID organizationId, UUID configId) {
        return Boolean.TRUE.equals(dsl.fetchValue("""
                SELECT previewed_at IS NOT NULL FROM pipeline_config
                WHERE organization_id = ? AND id = ? AND lifecycle_status = 'DRAFT'
                """, organizationId, configId, Boolean.class));
    }

    private Map<String, Object> settingsMap(PipelineConfig config) {
        return Map.<String, Object>ofEntries(
                Map.entry("keywordTopK", config.keywordTopK()), Map.entry("semanticTopK", config.semanticTopK()),
                Map.entry("rrfCandidateLimit", config.rrfCandidateLimit()),
                Map.entry("rerankCandidateLimit", config.rerankCandidateLimit()),
                Map.entry("finalContextGroups", config.finalContextGroups()),
                Map.entry("contextTokenBudget", config.contextTokenBudget()),
                Map.entry("minimumRerankScore", config.minimumRerankScore()),
                Map.entry("fastTimeoutSeconds", config.fastTimeoutSeconds()),
                Map.entry("maxIterations", config.maxIterations()),
                Map.entry("maxRetrievalRounds", config.maxRetrievalRounds()),
                Map.entry("maxSubQueries", config.maxSubQueries()), Map.entry("maxSearchCalls", config.maxSearchCalls()),
                Map.entry("maxDeepReadCalls", config.maxDeepReadCalls()),
                Map.entry("maxToolCallsPerRound", config.maxToolCallsPerRound()),
                Map.entry("maxFinalReferences", config.maxFinalReferences()), Map.entry("recentTurns", config.recentTurns()),
                Map.entry("maxContextTokens", config.maxContextTokens()),
                Map.entry("llmTimeoutSeconds", config.llmTimeoutSeconds()),
                Map.entry("agenticLoopTimeoutSeconds", config.agenticLoopTimeoutSeconds()),
                Map.entry("toolTimeoutSeconds", config.toolTimeoutSeconds()),
                Map.entry("maxCompletionTokens", config.maxCompletionTokens()),
                Map.entry("temperature", config.temperature()), Map.entry("parallelToolCalls", config.parallelToolCalls()),
                Map.entry("requireDeepReadBeforeAnswer", config.requireDeepReadBeforeAnswer()));
    }

    private PipelineConfig map(Record record) {
        var settings = settings(record.get("settings", org.jooq.JSONB.class));
        return new PipelineConfig(
                record.get("id", UUID.class), record.get("organization_id", UUID.class),
                record.get("name", String.class), record.get("pipeline_version", String.class),
                record.get("prompt_version", String.class), record.get("chat_profile_id", UUID.class),
                record.get("query_rewrite_profile_id", UUID.class), record.get("rerank_profile_id", UUID.class),
                integer(settings, "keywordTopK", 30), integer(settings, "semanticTopK", 30),
                integer(settings, "rrfCandidateLimit", 40), integer(settings, "rerankCandidateLimit", 20),
                integer(settings, "finalContextGroups", 8), integer(settings, "contextTokenBudget", 6000),
                decimal(settings, "minimumRerankScore", 0.05), integer(settings, "fastTimeoutSeconds", 45),
                integer(settings, "maxIterations", 35), integer(settings, "maxRetrievalRounds", 5),
                integer(settings, "maxSubQueries", 8), integer(settings, "maxSearchCalls", 16),
                integer(settings, "maxDeepReadCalls", 20), integer(settings, "maxToolCallsPerRound", 6),
                integer(settings, "maxFinalReferences", 16), integer(settings, "recentTurns", 5),
                integer(settings, "maxContextTokens", 200_000), integer(settings, "llmTimeoutSeconds", 120),
                integer(settings, "agenticLoopTimeoutSeconds", 300),
                integer(settings, "toolTimeoutSeconds", 60), integer(settings, "maxCompletionTokens", 2_048),
                decimal(settings, "temperature", 0.7), Boolean.TRUE.equals(settings.getOrDefault("parallelToolCalls", false)),
                !Boolean.FALSE.equals(settings.getOrDefault("requireDeepReadBeforeAnswer", true)),
                Boolean.TRUE.equals(record.get("active", Boolean.class)),
                record.get("created_at", OffsetDateTime.class).toInstant(),
                record.get("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private Map<String, Object> settings(org.jooq.JSONB value) {
        if (value == null) return Map.of();
        try {
            return objectMapper.readValue(value.data(), new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Pipeline Config settings are invalid", exception);
        }
    }

    private int integer(Map<String, Object> settings, String key, int fallback) {
        var value = settings.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private double decimal(Map<String, Object> settings, String key, double fallback) {
        var value = settings.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize Pipeline Config", exception);
        }
    }
}
