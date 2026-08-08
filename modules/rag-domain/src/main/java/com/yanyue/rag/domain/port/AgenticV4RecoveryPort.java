package com.yanyue.rag.domain.port;

import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.BudgetReservation;
import com.yanyue.rag.domain.agent.v4.ResearchHealth;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AgenticV4RecoveryPort {
    List<RecoverableRun> findRecoverableRuns();

    Optional<RecoverySnapshot> loadSnapshot(UUID runId);

    void prepareForRecovery(UUID runId);

    record RecoverableRun(
            UUID runId,
            UUID organizationId,
            UUID userId,
            UUID conversationId,
            CreateRunRequest request
    ) {
    }

    record GoalOutcome(
            UUID goalId,
            ResearchPhase phase,
            ResearchHealth health,
            List<UUID> acceptedEvidenceIds,
            boolean mayHaveHiddenEvidence
    ) {
        public GoalOutcome {
            acceptedEvidenceIds = List.copyOf(acceptedEvidenceIds);
        }
    }

    record RecoverySnapshot(
            String stage,
            Map<String, Object> checkpointState,
            Instant runStartedAt,
            List<AcceptedEvidence> evidence,
            List<GoalOutcome> goalOutcomes,
            List<BudgetReservation> reservations,
            Set<String> nonReplayableActionKeys,
            Map<String, Object> judgeReport
    ) {
        public RecoverySnapshot {
            checkpointState = Map.copyOf(checkpointState);
            evidence = List.copyOf(evidence);
            goalOutcomes = List.copyOf(goalOutcomes);
            reservations = List.copyOf(reservations);
            nonReplayableActionKeys = Set.copyOf(nonReplayableActionKeys);
            judgeReport = judgeReport == null ? Map.of() : Map.copyOf(judgeReport);
        }
    }
}
