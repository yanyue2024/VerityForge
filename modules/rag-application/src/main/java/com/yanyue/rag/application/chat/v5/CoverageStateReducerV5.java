package com.yanyue.rag.application.chat.v5;

import com.yanyue.rag.domain.agent.v5.CoverageState;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class CoverageStateReducerV5 {
    public CoverageState fromJudge(EvidenceJudgeReasonerV5.JudgeDecision decision) {
        var requirements = new LinkedHashMap<java.util.UUID, com.yanyue.rag.domain.agent.v5.RequirementStatus>();
        var goals = new LinkedHashMap<java.util.UUID, com.yanyue.rag.domain.agent.v5.GoalStatus>();
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
