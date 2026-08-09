package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.agent.deep.AcceptedEvidence;
import com.yanyue.rag.domain.agent.budget.BudgetReservation;
import com.yanyue.rag.domain.agent.deep.ResearchHealth;
import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.SearchQuery;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DeepRunArtifactPort {
    void checkpoint(UUID runId, String stage, Map<String, Object> state);

    void saveEvidence(UUID runId, AcceptedEvidence evidence);

    void reserveSearch(UUID runId, BudgetReservation reservation, SearchQuery query);

    boolean claimSearch(UUID runId, UUID reservationId);

    void saveRetrievalCandidates(UUID runId, SearchQuery query, List<RetrievalHit> hits);

    void completeSearch(
            UUID runId,
            UUID reservationId,
            boolean succeeded,
            int resultCount,
            String errorCategory
    );

    void reserveOperation(
            UUID runId,
            UUID goalId,
            String phase,
            String operation,
            BudgetReservation reservation
    );

    boolean claimOperation(UUID reservationId);

    void completeOperation(UUID reservationId, boolean succeeded, String errorCategory);

    void reserveModelAttempt(
            UUID runId,
            UUID logicalCallId,
            UUID goalId,
            String phase,
            String operation,
            String promptVersion,
            int attemptNumber,
            BudgetReservation reservation,
            int promptLength
    );

    boolean claimModelAttempt(UUID reservationId);

    void completeModelAttempt(
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
    );

    void completeLogicalModelCall(
            UUID logicalCallId,
            boolean succeeded,
            boolean repairUsed,
            String errorCategory,
            String resultHash
    );

    void saveGoalOutcome(
            UUID runId,
            UUID goalId,
            ResearchPhase phase,
            ResearchHealth health,
            List<UUID> searchTaskIds,
            UUID deepReadLogicalCallId,
            List<UUID> acceptedEvidenceIds,
            boolean mayHaveHiddenEvidence
    );

    void saveJudgeDecision(UUID runId, boolean sufficient, boolean degraded, Map<String, Object> report);
}
