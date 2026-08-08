package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.evaluation.EvaluationCaseAttempt;
import com.yanyue.rag.domain.evaluation.EvaluationRunLineage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationAttemptPort {
    void saveRequestSnapshot(UUID runId, Map<String, Object> requestSnapshot);

    void linkResumedRun(UUID runId, UUID resumedFromRunId, Map<String, Object> requestSnapshot);

    Optional<EvaluationRunLineage> loadLineage(UUID runId);

    void saveCaseAttempt(EvaluationCaseAttempt attempt);

    List<EvaluationCaseAttempt> loadCaseAttempts(UUID runId, UUID caseId);
}
