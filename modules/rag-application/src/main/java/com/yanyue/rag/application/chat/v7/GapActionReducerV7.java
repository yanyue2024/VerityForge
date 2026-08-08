package com.yanyue.rag.application.chat.v7;

import com.yanyue.rag.application.chat.v5.EvidenceJudgeReasonerV5;
import com.yanyue.rag.domain.agent.v5.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v5.GoalStatus;
import com.yanyue.rag.domain.agent.v5.RequestAnalysis;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Converts the single Judge result into a bounded, deterministic repair plan.
 * Precision-first v7 sends every incomplete Goal through the Judge's bounded
 * dual-route repair pair. Document-local READ_MORE remains disabled until it
 * proves that it adds missing Requirement coverage instead of duplicate evidence.
 */
@Component
public final class GapActionReducerV7 {
    public List<Action> reduce(
            RequestAnalysis analysis,
            EvidenceJudgeReasonerV5.JudgeDecision decision,
            GoalEvidencePool pool
    ) {
        return decision.goals().stream()
                .filter(value -> value.status() == GoalStatus.NEEDS_REPAIR)
                .map(value -> new Action(value.goalId(), Type.SEARCH_MORE,
                        value.requirements().stream()
                                .filter(requirement -> requirement.status()
                                        == com.yanyue.rag.domain.agent.v5.RequirementStatus.MISSING)
                                .map(EvidenceJudgeReasonerV5.RequirementDecision::requirementId)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())))
                .toList();
    }

    public record Action(UUID goalId, Type type, Set<UUID> missingRequirementIds) {
        public Action {
            missingRequirementIds = Set.copyOf(new LinkedHashSet<>(missingRequirementIds));
        }
    }

    public enum Type {
        NONE,
        READ_MORE,
        SEARCH_MORE,
        CONFLICT_CHECK
    }
}
