package com.yanyue.rag.api.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.contract.chat.AgentRunArtifactsView;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.port.AgentReactPersistencePort;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runs/{runId}/artifacts")
public class RunArtifactsController {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final AgentReactPersistencePort reactPersistence;

    public RunArtifactsController(DSLContext dsl, ObjectMapper objectMapper, AgentReactPersistencePort reactPersistence) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.reactPersistence = reactPersistence;
    }

    @GetMapping
    public AgentRunArtifactsView artifacts(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID runId
    ) {
        var run = dsl.fetchOptional("""
                SELECT status, requested_mode, selected_mode, query_text, runtime_snapshot
                FROM rag_run
                WHERE id = ? AND organization_id = ?
                  AND (created_by = ? OR EXISTS (
                      SELECT 1 FROM app_user u
                      WHERE u.id = ? AND u.organization_id = rag_run.organization_id
                        AND u.enabled = true AND u.role = 'ADMIN'
                  ))
                """, runId, user.organizationId(), user.userId(), user.userId())
                .orElseThrow(() -> new IllegalArgumentException("Run not found"));
        var checkpoint = dsl.fetchOptional(
                        "SELECT state FROM agent_run_checkpoint WHERE run_id = ?", runId)
                .map(record -> jsonMap(record.get("state", JSONB.class))).orElse(Map.of());
        var tasks = dsl.fetch("""
                SELECT id, sub_question_id, round_number, query_text, search_mode, status,
                       result_count, error_message, created_at, started_at, completed_at
                FROM agent_retrieval_task WHERE run_id = ? ORDER BY round_number, created_at
                """, runId).map(record -> mapOf(
                "id", record.get("id", UUID.class),
                "subQuestionId", record.get("sub_question_id", UUID.class),
                "round", record.get("round_number", Integer.class),
                "query", record.get("query_text", String.class),
                "searchMode", record.get("search_mode", String.class),
                "status", record.get("status", String.class),
                "resultCount", record.get("result_count", Integer.class),
                "errorMessage", record.get("error_message", String.class),
                "createdAt", record.get("created_at", OffsetDateTime.class),
                "startedAt", record.get("started_at", OffsetDateTime.class),
                "completedAt", record.get("completed_at", OffsetDateTime.class)
        ));
        var evidence = dsl.fetch("""
                SELECT id, sub_question_id, document_id, document_version_id, chunk_id, quote_text,
                       source_start, source_end, retrieval_score, deep_read, retrieval_sources, created_at
                FROM evidence_item
                WHERE run_id = ? AND document_is_accessible(document_id, ?)
                ORDER BY created_at
                """, runId, user.userId()).map(record -> mapOf(
                "id", record.get("id", UUID.class),
                "subQuestionId", record.get("sub_question_id", UUID.class),
                "documentId", record.get("document_id", UUID.class),
                "documentVersionId", record.get("document_version_id", UUID.class),
                "chunkId", record.get("chunk_id", UUID.class),
                "quote", record.get("quote_text", String.class),
                "sourceStart", record.get("source_start", Integer.class),
                "sourceEnd", record.get("source_end", Integer.class),
                "retrievalScore", record.get("retrieval_score", Double.class),
                "deepRead", record.get("deep_read", Boolean.class),
                "retrievalSources", record.get("retrieval_sources", String[].class),
                "createdAt", record.get("created_at", OffsetDateTime.class)
        ));
        var facts = dsl.fetch("""
                SELECT id, sub_question_id, statement, evidence_ids, confidence, status,
                       conflict_group_id, rejection_reason, supports, valid_from, valid_to, created_at
                FROM fact_item fact
                WHERE run_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM unnest(fact.evidence_ids) AS ids(evidence_id)
                      JOIN evidence_item evidence ON evidence.id = ids.evidence_id
                      WHERE NOT document_is_accessible(evidence.document_id, ?)
                  )
                ORDER BY created_at
                """, runId, user.userId()).map(record -> mapOf(
                "id", record.get("id", UUID.class),
                "subQuestionId", record.get("sub_question_id", UUID.class),
                "statement", record.get("statement", String.class),
                "evidenceIds", record.get("evidence_ids", UUID[].class),
                "confidence", record.get("confidence", java.math.BigDecimal.class),
                "status", record.get("status", String.class),
                "conflictGroupId", record.get("conflict_group_id", UUID.class),
                "rejectionReason", record.get("rejection_reason", String.class),
                "supports", jsonValue(record.get("supports", JSONB.class)),
                "validFrom", record.get("valid_from", OffsetDateTime.class),
                "validTo", record.get("valid_to", OffsetDateTime.class),
                "createdAt", record.get("created_at", OffsetDateTime.class)
        ));
        var coverage = dsl.fetch("""
                SELECT round_number, sufficient, report, created_at
                FROM coverage_report WHERE run_id = ? ORDER BY round_number
                """, runId).map(record -> mapOf(
                "round", record.get("round_number", Integer.class),
                "sufficient", record.get("sufficient", Boolean.class),
                "report", jsonMap(record.get("report", JSONB.class)),
                "createdAt", record.get("created_at", OffsetDateTime.class)
        ));
        var react = reactPersistence.loadArtifacts(runId).orElse(null);
        var reactSteps = react == null ? List.<Map<String, Object>>of()
                : react.steps().stream().map(value -> objectMapper.convertValue(value, MAP_TYPE)).toList();
        var toolCalls = react == null ? List.<Map<String, Object>>of()
                : react.toolCalls().stream().map(value -> objectMapper.convertValue(value, MAP_TYPE)).toList();
        var knowledgeReferences = react == null ? List.<Map<String, Object>>of()
                : react.knowledgeReferences().stream().map(value -> objectMapper.convertValue(value, MAP_TYPE)).toList();
        var rankedDocuments = react == null ? List.<Map<String, Object>>of()
                : react.rankedDocuments().stream().map(value -> objectMapper.convertValue(value, MAP_TYPE)).toList();
        return new AgentRunArtifactsView(
                runId, run.get("status", String.class), RunMode.valueOf(run.get("requested_mode", String.class)),
                run.get("selected_mode", String.class) == null ? null
                        : RunMode.valueOf(run.get("selected_mode", String.class)),
                run.get("query_text", String.class), jsonMap(run.get("runtime_snapshot", JSONB.class)),
                checkpoint, tasks, evidence, facts, coverage,
                react == null ? 1 : react.artifactVersion(), reactSteps, toolCalls, knowledgeReferences,
                rankedDocuments, react == null ? Map.of() : react.budgetSnapshot()
        );
    }

    private Map<String, Object> jsonMap(JSONB value) {
        if (value == null) return Map.of();
        try {
            return objectMapper.readValue(value.data(), MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Run artifact JSON is invalid", exception);
        }
    }

    private Object jsonValue(JSONB value) {
        if (value == null) return null;
        try {
            return objectMapper.readValue(value.data(), Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Run artifact JSON is invalid", exception);
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
