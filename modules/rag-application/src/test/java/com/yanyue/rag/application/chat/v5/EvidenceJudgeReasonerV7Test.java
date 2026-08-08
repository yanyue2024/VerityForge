package com.yanyue.rag.application.chat.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.v4.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.v4.RequirementPlan;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v5.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v5.GoalPlan;
import com.yanyue.rag.domain.agent.v5.QueryPair;
import com.yanyue.rag.domain.agent.v5.RequestAnalysis;
import com.yanyue.rag.domain.agent.v5.SearchQuery;
import com.yanyue.rag.domain.agent.v5.SearchQueryRole;
import com.yanyue.rag.domain.agent.v7.AgenticV7Limits;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceJudgeReasonerV7Test {
    @Test
    void v7RepairKeywordUsesTheSameCanonicalPolicyAsPrimarySearch() {
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var requirement = new RequirementPlan(requirementId, goalId, "CRI 接口的定义和职责");
        var targets = Set.of(requirementId);
        var primary = new QueryPair(goalId, ResearchPhase.PRIMARY,
                new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                        SearchQueryRole.PRIMARY_KEYWORD, "CRI接口", SearchMode.KEYWORD, targets),
                new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                        SearchQueryRole.PRIMARY_SEMANTIC, "CRI 接口如何定义", SearchMode.SEMANTIC, targets));
        var goal = new GoalPlan(goalId, "CRI调用边界中的描述是什么？", List.of(requirement), primary);
        var analysis = new RequestAnalysis("说明 CRI 调用边界",
                List.of(new ObjectiveRequirement(UUID.randomUUID(), "回答 CRI 调用边界", true, Set.of(goalId))),
                List.of(), List.of(goal));
        var pool = new GoalEvidencePool(analysis, AgenticV7Limits.defaults());
        var raw = """
                {
                  "goalDecisions":[{
                    "goalId":"%s",
                    "requirementDecisions":[{
                      "requirementId":"%s","status":"MISSING","evidenceIds":[]
                    }],
                    "repairQueries":[
                      {"role":"REPAIR_KEYWORD","searchMode":"KEYWORD","text":"CRI调用边界","targetRequirementIds":["%s"]},
                      {"role":"REPAIR_SEMANTIC","searchMode":"SEMANTIC","text":"CRI 接口的定义和职责","targetRequirementIds":["%s"]}
                    ]
                  }]
                }
                """.formatted(goalId, requirementId, requirementId, requirementId);

        var decision = new EvidenceJudgeReasonerV5(null, new ObjectMapper())
                .parseV7(raw, analysis, pool, Map.of());

        assertEquals("CRI接口 OR CRI",
                decision.goals().getFirst().repairQueryPair().keywordQuery().text());
    }
}
