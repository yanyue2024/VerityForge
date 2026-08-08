package com.yanyue.rag.application.chat.v4;

import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.GoalStatus;
import com.yanyue.rag.domain.agent.v4.RepairCompletionMode;
import com.yanyue.rag.domain.agent.v4.RepairTarget;
import com.yanyue.rag.domain.agent.v4.RepairTargetStatus;
import com.yanyue.rag.domain.agent.v4.RequirementStatus;
import com.yanyue.rag.domain.agent.v4.TargetEffect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CoverageStateReducer {
    public CoverageState fromJudge(EvidenceJudgeReasoner.JudgeDecision decision) {
        var requirements = new LinkedHashMap<UUID, RequirementStatus>();
        var goals = new LinkedHashMap<UUID, GoalStatus>();
        var targets = new LinkedHashMap<UUID, RepairTarget>();
        for (var goal : decision.goals()) {
            goal.requirements().forEach(value -> requirements.put(value.requirementId(), value.status()));
            goal.repairTargets().forEach(value -> targets.put(value.id(), value));
            goals.put(goal.goalId(), goal.goalStatus());
        }
        return new CoverageState(requirements, goals, targets, decision.degraded());
    }

    public CoverageState afterRepair(
            CoverageState current,
            EvidenceJudgeReasoner.JudgeDecision decision,
            List<AcceptedEvidence> newEvidence
    ) {
        var requirements = new LinkedHashMap<>(current.requirementStatuses());
        var goals = new LinkedHashMap<>(current.goalStatuses());
        var targets = new LinkedHashMap<>(current.repairTargets());
        for (var evidence : newEvidence) {
            for (var link : evidence.requirementLinks()) {
                if (link.repairTargetId() == null || link.targetEffect() != TargetEffect.COMPLETE) continue;
                var target = targets.get(link.repairTargetId());
                if (target == null || target.status() != RepairTargetStatus.OPEN
                        || target.completionMode() != RepairCompletionMode.SINGLE_SPAN_COMPLETABLE) continue;
                targets.put(target.id(), target.apply(TargetEffect.COMPLETE));
            }
        }
        for (var goal : decision.goals()) {
            for (var requirement : goal.requirements()) {
                if (requirement.status() == RequirementStatus.COVERED
                        || requirement.status() == RequirementStatus.CONFLICTING) continue;
                var target = requirement.repairTarget();
                var updated = target == null ? null : targets.get(target.id());
                requirements.put(requirement.requirementId(), updated != null
                        && updated.status() == RepairTargetStatus.SATISFIED
                        ? RequirementStatus.COVERED : RequirementStatus.NOT_FOUND_WITHIN_BUDGET);
            }
            boolean covered = goal.requirements().stream().allMatch(value ->
                    requirements.get(value.requirementId()) == RequirementStatus.COVERED);
            goals.put(goal.goalId(), covered ? GoalStatus.SATISFIED_LOCKED : GoalStatus.PARTIAL_EXHAUSTED);
        }
        return new CoverageState(requirements, goals, targets, current.judgeDegraded());
    }

    public record CoverageState(
            Map<UUID, RequirementStatus> requirementStatuses,
            Map<UUID, GoalStatus> goalStatuses,
            Map<UUID, RepairTarget> repairTargets,
            boolean judgeDegraded
    ) {
        public CoverageState {
            requirementStatuses = Map.copyOf(requirementStatuses);
            goalStatuses = Map.copyOf(goalStatuses);
            repairTargets = Map.copyOf(repairTargets);
        }

        public List<UUID> incompleteGoalIds() {
            return goalStatuses.entrySet().stream()
                    .filter(value -> value.getValue() != GoalStatus.SATISFIED_LOCKED)
                    .map(Map.Entry::getKey).toList();
        }
    }
}
