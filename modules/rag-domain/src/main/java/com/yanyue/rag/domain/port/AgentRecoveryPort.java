package com.yanyue.rag.domain.port;

import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.domain.agent.AgentRunState;
import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.FactItem;
import com.yanyue.rag.domain.agent.QuestionPlan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AgentRecoveryPort {
    List<RecoverableRun> findRecoverableRuns();

    Optional<RecoverySnapshot> loadSnapshot(UUID runId);

    void resetIncompleteReasoning(UUID runId);

    record RecoverableRun(
            UUID runId,
            UUID organizationId,
            UUID userId,
            UUID conversationId,
            CreateRunRequest request
    ) {
    }

    record RecoverySnapshot(
            AgentRunState state,
            QuestionPlan plan,
            List<FactItem> facts,
            Map<UUID, RetrievalHit> evidenceHits,
            CoverageReport coverage
    ) {
        public RecoverySnapshot {
            facts = facts == null ? List.of() : List.copyOf(facts);
            evidenceHits = evidenceHits == null ? Map.of() : Map.copyOf(evidenceHits);
        }
    }
}
