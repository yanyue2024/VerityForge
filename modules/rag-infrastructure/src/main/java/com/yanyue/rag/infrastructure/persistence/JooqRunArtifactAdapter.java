package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.AgentRunState;
import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.EvidenceItem;
import com.yanyue.rag.domain.agent.FactItem;
import com.yanyue.rag.domain.agent.QuestionPlan;
import com.yanyue.rag.domain.agent.RetrievalTask;
import com.yanyue.rag.domain.port.AgentRunArtifactPort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqRunArtifactAdapter implements AgentRunArtifactPort, CitationPort {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqRunArtifactAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public void checkpoint(AgentRunState state, QuestionPlan plan) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("runId", state.runId());
        payload.put("stage", state.stage());
        payload.put("budget", state.budget());
        payload.put("createdAt", state.createdAt());
        payload.put("updatedAt", state.updatedAt());
        if (plan != null) payload.put("plan", plan);
        dsl.execute("""
                INSERT INTO agent_run_checkpoint (run_id, stage, state, updated_at)
                VALUES (?, ?, ?::jsonb, now())
                ON CONFLICT (run_id) DO UPDATE
                SET stage = EXCLUDED.stage, state = EXCLUDED.state, updated_at = now()
                """, state.runId(), state.stage().name(), json(payload));
    }

    @Override
    public void saveEvidence(UUID runId, EvidenceItem evidence) {
        dsl.execute("""
                INSERT INTO evidence_item
                    (id, run_id, sub_question_id, document_id, document_version_id, chunk_id,
                     quote_text, source_start, source_end, retrieval_score, deep_read, retrieval_sources)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE
                SET quote_text = EXCLUDED.quote_text,
                    source_start = EXCLUDED.source_start,
                    source_end = EXCLUDED.source_end,
                    retrieval_score = EXCLUDED.retrieval_score,
                    deep_read = EXCLUDED.deep_read,
                    retrieval_sources = EXCLUDED.retrieval_sources
                """, evidence.id(), runId, evidence.subQuestionId(), evidence.documentId(),
                evidence.documentVersionId(), evidence.chunkId(), evidence.quote(), evidence.sourceStart(),
                evidence.sourceEnd(), evidence.retrievalScore(), evidence.deepRead(),
                evidence.retrievalSources().toArray(String[]::new));
    }

    @Override
    public void saveFact(UUID runId, FactItem fact) {
        dsl.execute("""
                INSERT INTO fact_item
                    (id, run_id, sub_question_id, statement, evidence_ids, confidence, status,
                     conflict_group_id, supports, rejection_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (id) DO UPDATE
                SET statement = EXCLUDED.statement,
                    evidence_ids = EXCLUDED.evidence_ids,
                    confidence = EXCLUDED.confidence,
                    status = EXCLUDED.status,
                    conflict_group_id = EXCLUDED.conflict_group_id,
                    supports = EXCLUDED.supports,
                    rejection_reason = EXCLUDED.rejection_reason
                """, fact.id(), runId, fact.subQuestionId(), fact.statement(),
                fact.evidenceIds().toArray(UUID[]::new), fact.confidence(), fact.status().name(),
                fact.conflictGroupId(), json(fact.supports()), fact.rejectionReason());
        dsl.execute("""
                UPDATE fact_item f
                SET valid_from = validity.valid_from,
                    valid_to = validity.valid_to
                FROM (
                    SELECT max(dv.valid_from) AS valid_from,
                           min(dv.valid_to) FILTER (WHERE dv.valid_to IS NOT NULL) AS valid_to
                    FROM evidence_item e
                    JOIN document_version dv ON dv.id = e.document_version_id
                    WHERE e.id = ANY(?::uuid[])
                ) validity
                WHERE f.id = ?
                """, fact.evidenceIds().toArray(UUID[]::new), fact.id());
    }

    @Override
    public void saveCoverage(UUID runId, int roundNumber, CoverageReport report) {
        dsl.execute("""
                INSERT INTO coverage_report (run_id, round_number, sufficient, report)
                VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (run_id, round_number) DO UPDATE
                SET sufficient = EXCLUDED.sufficient, report = EXCLUDED.report, created_at = now()
                """, runId, roundNumber, report.sufficient(), json(report));
    }

    @Override
    public void saveTask(
            UUID runId,
            int roundNumber,
            RetrievalTask task,
            String status,
            int resultCount,
            String errorMessage
    ) {
        dsl.execute("""
                INSERT INTO agent_retrieval_task
                    (id, run_id, sub_question_id, round_number, query_text, search_mode,
                     status, result_count, error_message, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                        CASE WHEN ? IN ('RUNNING', 'SUCCEEDED', 'FAILED') THEN now() ELSE NULL END,
                        CASE WHEN ? IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN now() ELSE NULL END)
                ON CONFLICT (id) DO UPDATE
                SET status = EXCLUDED.status,
                    result_count = EXCLUDED.result_count,
                    error_message = EXCLUDED.error_message,
                    started_at = COALESCE(agent_retrieval_task.started_at, EXCLUDED.started_at),
                    completed_at = EXCLUDED.completed_at
                """, task.id(), runId, task.subQuestionId(), roundNumber, task.query(), task.searchMode().name(),
                status, resultCount, errorMessage, status, status);
    }

    @Override
    public void annotateCheckpoint(UUID runId, Map<String, Object> details) {
        dsl.execute("""
                UPDATE agent_run_checkpoint
                SET state = state || ?::jsonb, updated_at = now()
                WHERE run_id = ?
                """, json(details), runId);
    }

    @Override
    public void save(UUID runId, int citationIndex, RetrievalHit hit) {
        dsl.execute("""
                INSERT INTO citation
                    (run_id, citation_index, document_id, document_version_id, chunk_id, quote_text,
                     source_start, source_end)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, citation_index) DO UPDATE
                SET document_id = EXCLUDED.document_id,
                    document_version_id = EXCLUDED.document_version_id,
                    chunk_id = EXCLUDED.chunk_id,
                    quote_text = EXCLUDED.quote_text,
                    source_start = EXCLUDED.source_start,
                    source_end = EXCLUDED.source_end
                """, runId, citationIndex, hit.documentId(), hit.documentVersionId(), hit.chunkId(), hit.text(),
                hit.sourceStart(), hit.sourceEnd());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize run artifact", exception);
        }
    }
}
