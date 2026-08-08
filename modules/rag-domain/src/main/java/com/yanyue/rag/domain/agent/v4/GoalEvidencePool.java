package com.yanyue.rag.domain.agent.v4;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GoalEvidencePool {
    private final Map<UUID, GoalPlan> goals;
    private final Map<EvidenceKey, AcceptedEvidence> evidenceByKey = new LinkedHashMap<>();
    private final Map<UUID, EvidenceKey> keyByEvidenceId = new LinkedHashMap<>();

    public GoalEvidencePool(RequestAnalysis analysis) {
        V4Validation.required(analysis, "analysis");
        var indexedGoals = new LinkedHashMap<UUID, GoalPlan>();
        analysis.goals().forEach(goal -> indexedGoals.put(goal.id(), goal));
        this.goals = Map.copyOf(indexedGoals);
    }

    /**
     * 调用方应先完成质量排序；证据池只执行归属、幂等和硬配额，不根据到达顺序猜测质量。
     */
    public synchronized AcceptedEvidence accept(AcceptedEvidence evidence) {
        V4Validation.required(evidence, "evidence");
        var goal = goals.get(evidence.goalId());
        if (goal == null || !goal.requirementIds().containsAll(evidence.activeRequirementIds())) {
            throw new IllegalArgumentException("evidence references an unknown goal or requirement");
        }
        var key = EvidenceKey.from(evidence);
        var existing = evidenceByKey.get(key);
        if (existing != null) {
            var merged = existing.mergeSameSpan(evidence);
            enforceNewRequirementQuotas(existing, merged);
            evidenceByKey.put(key, merged);
            return merged;
        }
        var evidenceIdKey = keyByEvidenceId.get(evidence.evidenceId());
        if (evidenceIdKey != null && !evidenceIdKey.equals(key)) {
            throw new IllegalArgumentException("evidenceId is already used by another source span");
        }
        enforceQuotas(evidence);
        evidenceByKey.put(key, evidence);
        keyByEvidenceId.put(evidence.evidenceId(), key);
        return evidence;
    }

    public synchronized List<AcceptedEvidence> forGoal(UUID goalId) {
        return evidenceByKey.values().stream().filter(evidence -> evidence.goalId().equals(goalId)).toList();
    }

    public synchronized List<AcceptedEvidence> forRequirement(UUID goalId, UUID requirementId) {
        return evidenceByKey.values().stream()
                .filter(evidence -> evidence.goalId().equals(goalId)
                        && evidence.activeRequirementIds().contains(requirementId))
                .toList();
    }

    public synchronized List<AcceptedEvidence> all() {
        return List.copyOf(new ArrayList<>(evidenceByKey.values()));
    }

    public synchronized int size() {
        return evidenceByKey.size();
    }

    private void enforceQuotas(AcceptedEvidence candidate) {
        if (evidenceByKey.size() >= AgenticV4Limits.MAX_ACCEPTED_EVIDENCE) {
            throw new IllegalStateException("run accepted evidence quota exceeded");
        }
        if (forGoal(candidate.goalId()).size() >= AgenticV4Limits.MAX_EVIDENCE_PER_GOAL) {
            throw new IllegalStateException("goal accepted evidence quota exceeded");
        }
        long sameParentAndPhase = evidenceByKey.values().stream()
                .filter(existing -> existing.goalId().equals(candidate.goalId())
                        && existing.parentChunkId().equals(candidate.parentChunkId())
                        && existing.firstAcceptedPhase() == candidate.firstAcceptedPhase())
                .count();
        if (sameParentAndPhase >= AgenticV4Limits.MAX_EVIDENCE_PER_PARENT_AND_PHASE) {
            throw new IllegalStateException("parent and phase accepted evidence quota exceeded");
        }
        for (var requirementId : candidate.activeRequirementIds()) {
            if (forRequirement(candidate.goalId(), requirementId).size()
                    >= AgenticV4Limits.MAX_EVIDENCE_PER_REQUIREMENT) {
                throw new IllegalStateException("requirement accepted evidence quota exceeded");
            }
        }
    }

    private void enforceNewRequirementQuotas(AcceptedEvidence existing, AcceptedEvidence merged) {
        var newlyLinked = new java.util.HashSet<>(merged.activeRequirementIds());
        newlyLinked.removeAll(existing.activeRequirementIds());
        for (var requirementId : newlyLinked) {
            if (forRequirement(merged.goalId(), requirementId).size()
                    >= AgenticV4Limits.MAX_EVIDENCE_PER_REQUIREMENT) {
                throw new IllegalStateException("requirement accepted evidence quota exceeded");
            }
        }
    }

    private record EvidenceKey(UUID goalId, UUID documentVersionId, String spanId) {
        static EvidenceKey from(AcceptedEvidence evidence) {
            return new EvidenceKey(evidence.goalId(), evidence.documentVersionId(), evidence.spanId());
        }
    }
}
