package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v5.GoalRankedCandidate;
import java.util.List;
import java.util.UUID;

public interface AgenticV5RankingPort {
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
