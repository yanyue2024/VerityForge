package com.yanyue.rag.domain.agent.v5;

import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.EvidenceLinkStatus;
import com.yanyue.rag.domain.agent.v4.EvidenceRequirementLink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GoalEvidencePool {
    private final Map<UUID, Set<UUID>> requirementsByGoal;
    private final Map<EvidenceKey, AcceptedEvidence> evidenceByKey = new LinkedHashMap<>();
    private final Map<UUID, List<String>> supportQuotesByEvidenceId = new LinkedHashMap<>();
    private final int maxAcceptedEvidence;
    private final int maxEvidencePerGoal;
    private final int maxEvidencePerRequirement;
    private final int maxEvidencePerParentAndPhase;

    public GoalEvidencePool(RequestAnalysis analysis) {
        this(analysis, AgenticV5Limits.defaults());
    }

    public GoalEvidencePool(RequestAnalysis analysis, AgenticV5Limits limits) {
        var indexed = new LinkedHashMap<UUID, Set<UUID>>();
        analysis.goals().forEach(goal -> indexed.put(goal.id(), goal.requirementIds()));
        requirementsByGoal = Map.copyOf(indexed);
        maxAcceptedEvidence = limits.acceptedEvidenceLimit();
        maxEvidencePerGoal = limits.evidencePerGoalLimit();
        maxEvidencePerRequirement = limits.evidencePerRequirementLimit();
        maxEvidencePerParentAndPhase = limits.evidencePerParentAndPhaseLimit();
    }

    public synchronized AcceptedEvidence accept(AcceptedEvidence evidence) {
        var requirements = requirementsByGoal.get(evidence.goalId());
        if (requirements == null || !requirements.containsAll(evidence.activeRequirementIds())) {
            throw new IllegalArgumentException("Evidence 引用了未知 Goal 或 Requirement");
        }
        var key = EvidenceKey.from(evidence);
        var existing = evidenceByKey.get(key);
        if (existing != null && existing.activeRequirementIds().containsAll(evidence.activeRequirementIds())
                && existing.firstAcceptedPhase() == evidence.firstAcceptedPhase()) {
            return existing;
        }
        boolean newEvidence = existing == null;
        var candidate = existing == null ? evidence : existing.mergeSameSpan(evidence);
        candidate = trimSaturatedRequirementLinks(candidate);
        enforceQuotas(candidate, key, newEvidence);
        evidenceByKey.put(key, candidate);
        return candidate;
    }

    public synchronized List<AcceptedEvidence> forGoal(UUID goalId) {
        return evidenceByKey.values().stream().filter(value -> value.goalId().equals(goalId)).toList();
    }

    public synchronized List<AcceptedEvidence> forRequirement(UUID goalId, UUID requirementId) {
        return evidenceByKey.values().stream().filter(value -> value.goalId().equals(goalId)
                && value.activeRequirementIds().contains(requirementId)).toList();
    }

    public synchronized List<AcceptedEvidence> all() {
        return List.copyOf(new ArrayList<>(evidenceByKey.values()));
    }

    public synchronized int size() {
        return evidenceByKey.size();
    }

    public synchronized void recordSupportQuotes(UUID evidenceId, List<String> supportQuotes) {
        var evidence = evidenceByKey.values().stream()
                .filter(value -> value.evidenceId().equals(evidenceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("supportQuote 引用了未知 Evidence"));
        var merged = new java.util.LinkedHashSet<>(supportQuotesByEvidenceId.getOrDefault(evidenceId, List.of()));
        for (var quote : supportQuotes == null ? List.<String>of() : supportQuotes) {
            var normalized = quote == null ? "" : quote.strip();
            if (normalized.isEmpty() || !evidence.quote().contains(normalized)) {
                throw new IllegalArgumentException("supportQuote 必须逐字存在于完整 Evidence 中");
            }
            merged.add(normalized);
        }
        supportQuotesByEvidenceId.put(evidenceId, List.copyOf(merged));
    }

    public synchronized List<String> supportQuotes(UUID evidenceId) {
        return supportQuotesByEvidenceId.getOrDefault(evidenceId, List.of());
    }

    private void enforceQuotas(AcceptedEvidence candidate, EvidenceKey key, boolean newEvidence) {
        if (newEvidence && (size() >= maxAcceptedEvidence
                || forGoal(candidate.goalId()).size() >= maxEvidencePerGoal)) {
            throw new IllegalStateException("Evidence 配额已耗尽");
        }
        long sameParent = forGoal(candidate.goalId()).stream().filter(existing ->
                !EvidenceKey.from(existing).equals(key)
                        && java.util.Objects.equals(existing.parentChunkId(), candidate.parentChunkId())
                        && existing.firstAcceptedPhase() == candidate.firstAcceptedPhase()).count();
        if (newEvidence && sameParent >= maxEvidencePerParentAndPhase) {
            throw new IllegalStateException("同一父块和阶段的 Evidence 配额已耗尽");
        }
        for (var requirementId : candidate.activeRequirementIds()) {
            long requirementEvidence = forRequirement(candidate.goalId(), requirementId).stream()
                    .filter(existing -> !EvidenceKey.from(existing).equals(key)).count();
            if (newEvidence && requirementEvidence
                    >= maxEvidencePerRequirement) {
                throw new IllegalStateException("Requirement Evidence 配额已耗尽");
            }
        }
    }

    /**
     * One span may support several requirements. A saturated requirement must
     * not discard the same span for another requirement that still needs help.
     */
    private AcceptedEvidence trimSaturatedRequirementLinks(AcceptedEvidence evidence) {
        var active = evidence.requirementLinks().stream()
                .filter(link -> link.status() == EvidenceLinkStatus.ACTIVE)
                .filter(link -> forRequirement(evidence.goalId(), link.requirementId()).size()
                        < maxEvidencePerRequirement)
                .toList();
        if (active.isEmpty()) throw new IllegalStateException("Evidence 的所有 Requirement 配额均已耗尽");
        if (active.size() == evidence.activeRequirementIds().size()) return evidence;
        var links = new java.util.ArrayList<EvidenceRequirementLink>();
        links.addAll(active);
        evidence.requirementLinks().stream()
                .filter(link -> link.status() != EvidenceLinkStatus.ACTIVE)
                .forEach(links::add);
        return new AcceptedEvidence(evidence.evidenceId(), evidence.goalId(), links, evidence.spanId(),
                evidence.documentId(), evidence.documentVersionId(), evidence.parentChunkId(), evidence.quote(),
                evidence.sourceAnchor(), evidence.titlePath(), evidence.pageRange(), evidence.retrievalScore(),
                evidence.firstAcceptedPhase(), evidence.querySourceIds(), evidence.retrievalSources());
    }

    private record EvidenceKey(UUID goalId, UUID documentVersionId, String spanId) {
        static EvidenceKey from(AcceptedEvidence evidence) {
            return new EvidenceKey(evidence.goalId(), evidence.documentVersionId(), evidence.spanId());
        }
    }
}
