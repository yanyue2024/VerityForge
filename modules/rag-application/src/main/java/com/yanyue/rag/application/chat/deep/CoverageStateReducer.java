package com.yanyue.rag.application.chat.deep;

import com.yanyue.rag.domain.agent.deep.CoverageState;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class CoverageStateReducer {
    public CoverageState fromJudge(EvidenceJudge.JudgeDecision decision) {
        var requirements = new LinkedHashMap<java.util.UUID, com.yanyue.rag.domain.agent.deep.RequirementStatus>();
        var goals = new LinkedHashMap<java.util.UUID, com.yanyue.rag.domain.agent.deep.GoalStatus>();
        for (var goal : decision.goals()) {
            if (goals.putIfAbsent(goal.goalId(), goal.status()) != null) {
                throw new IllegalArgumentException("Judge 返回了重复 Goal");
            }
            for (var requirement : goal.requirements()) {
                if (requirements.putIfAbsent(requirement.requirementId(), requirement.status()) != null) {
                    throw new IllegalArgumentException("Judge 返回了重复 Requirement");
                }
            }
        }
        return new CoverageState(requirements, goals, decision.degraded());
    }
}
