package com.yanyue.rag.application.chat.v5;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v5.GoalStatus;
import com.yanyue.rag.domain.agent.v5.QueryPair;
import com.yanyue.rag.domain.agent.v5.SearchQuery;
import com.yanyue.rag.domain.agent.v5.SearchQueryRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgenticRagV5PipelineTest {
    @Test
    void 补检调度应跳过已锁定且没有补检查询的目标() {
        var lockedGoalId = UUID.randomUUID();
        var repairGoalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var repairPair = new QueryPair(repairGoalId, ResearchPhase.REPAIR,
                new SearchQuery(UUID.randomUUID(), repairGoalId, ResearchPhase.REPAIR,
                        SearchQueryRole.REPAIR_KEYWORD, "精确关键词", SearchMode.KEYWORD, Set.of(requirementId)),
                new SearchQuery(UUID.randomUUID(), repairGoalId, ResearchPhase.REPAIR,
                        SearchQueryRole.REPAIR_SEMANTIC, "完整语义问题", SearchMode.SEMANTIC, Set.of(requirementId)));
        var decision = new EvidenceJudgeReasonerV5.JudgeDecision(List.of(
                new EvidenceJudgeReasonerV5.GoalDecision(
                        lockedGoalId, List.of(), null, GoalStatus.SATISFIED_LOCKED),
                new EvidenceJudgeReasonerV5.GoalDecision(
                        repairGoalId, List.of(), repairPair, GoalStatus.NEEDS_REPAIR)), false);

        assertNull(AgenticRagV5Pipeline.repairPairFor(decision, lockedGoalId));
        assertSame(repairPair, AgenticRagV5Pipeline.repairPairFor(decision, repairGoalId));
    }
}
