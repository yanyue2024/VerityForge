package com.yanyue.rag.application.chat.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.deep.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.deep.RequirementPlan;
import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.SearchMode;
import com.yanyue.rag.domain.agent.deep.GoalEvidencePool;
import com.yanyue.rag.domain.agent.deep.GoalPlan;
import com.yanyue.rag.domain.agent.deep.QueryPair;
import com.yanyue.rag.domain.agent.deep.RequestAnalysis;
import com.yanyue.rag.domain.agent.deep.SearchQuery;
import com.yanyue.rag.domain.agent.deep.SearchQueryRole;
import com.yanyue.rag.domain.agent.deep.DeepRagProfiles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceJudgeTest {
    @Test
    void repairKeywordUsesTheSameCanonicalPolicyAsPrimarySearch() {
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
        var pool = new GoalEvidencePool(analysis, DeepRagProfiles.finalProfile());
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

        var decision = new EvidenceJudge(null, new ObjectMapper())
                .parse(raw, analysis, pool, Map.of());

        assertEquals("CRI接口 OR CRI OR CRI调用边界",
                decision.goals().getFirst().repairQueryPair().keywordQuery().text());
    }
}
