package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.GoalRankedCandidate;
import java.util.List;
import java.util.UUID;

public interface GoalRankingPort {
    void saveGoalRankedCandidates(
            UUID runId,
            UUID goalId,
            int goalOrder,
            ResearchPhase phase,
            List<GoalRankedCandidate> candidates
    );

    List<GoalRankedCandidate> loadGoalRankedCandidates(
            UUID runId,
            UUID goalId,
            ResearchPhase phase
    );
}
