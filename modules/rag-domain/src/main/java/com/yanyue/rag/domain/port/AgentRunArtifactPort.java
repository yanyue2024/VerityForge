package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.agent.AgentRunState;
import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.EvidenceItem;
import com.yanyue.rag.domain.agent.FactItem;
import com.yanyue.rag.domain.agent.QuestionPlan;
import com.yanyue.rag.domain.agent.RetrievalTask;
import java.util.UUID;
import java.util.Map;

public interface AgentRunArtifactPort {
    void checkpoint(AgentRunState state, QuestionPlan plan);
    void saveEvidence(UUID runId, EvidenceItem evidence);
    void saveFact(UUID runId, FactItem fact);
    void saveCoverage(UUID runId, int roundNumber, CoverageReport report);
    void saveTask(UUID runId, int roundNumber, RetrievalTask task, String status, int resultCount, String errorMessage);
    void annotateCheckpoint(UUID runId, Map<String, Object> details);
}
