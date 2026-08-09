package com.yanyue.rag.application.chat.deep;

import com.yanyue.rag.application.chat.deep.DeepModelInvoker;
import com.yanyue.rag.domain.agent.deep.AcceptedEvidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds one token-bounded final-answer entry per immutable parent chunk. */
public final class FinalAnswerEvidencePackBuilder {
    private static final int SERIALIZATION_TOKENS_PER_EVIDENCE = 96;

    public Pack build(List<AcceptedEvidence> evidence, int tokenBudget) {
        if (evidence == null || evidence.isEmpty() || tokenBudget < 1) {
            return new Pack(List.of(), 0, 0);
        }
        var groups = mergeParents(evidence);
        var remaining = new ArrayList<>(groups.values());
        remaining.sort(candidateOrder());
        var selected = new ArrayList<PackedEvidence>();
        var coveredGoals = new LinkedHashSet<UUID>();
        var coveredRequirements = new LinkedHashSet<RequirementKey>();
        int usedTokens = 0;

        while (true) {
            PackedEvidence best = null;
            int bestGain = 0;
            for (var candidate : remaining) {
                if (usedTokens + candidate.estimatedTokens() > tokenBudget) continue;
                int gain = coverageGain(candidate, coveredGoals, coveredRequirements);
                if (gain > bestGain || gain == bestGain && gain > 0
                        && betterCoverageCandidate(candidate, best)) {
                    best = candidate;
                    bestGain = gain;
                }
            }
            if (best == null || bestGain == 0) break;
            selected.add(best);
            remaining.remove(best);
            usedTokens += best.estimatedTokens();
            coveredGoals.addAll(best.goalIds());
            coveredRequirements.addAll(best.requirementKeys());
        }

        for (var candidate : List.copyOf(remaining)) {
            if (usedTokens + candidate.estimatedTokens() > tokenBudget) continue;
            selected.add(candidate);
            usedTokens += candidate.estimatedTokens();
        }

        if (selected.isEmpty() && !groups.isEmpty()) {
            var fallback = groups.values().stream().min(Comparator
                    .comparingInt(PackedEvidence::estimatedTokens)
                    .thenComparing(candidateOrder())).orElseThrow();
            selected.add(fallback);
            usedTokens = fallback.estimatedTokens();
        }
        return new Pack(List.copyOf(selected), usedTokens, groups.size());
    }

    private Map<ParentKey, PackedEvidence> mergeParents(List<AcceptedEvidence> evidence) {
        var groups = new LinkedHashMap<ParentKey, MutableParent>();
        for (var item : evidence) {
            var key = new ParentKey(item.documentVersionId(), item.parentChunkId());
            groups.computeIfAbsent(key, ignored -> new MutableParent(item)).merge(item);
        }
        var result = new LinkedHashMap<ParentKey, PackedEvidence>();
        groups.forEach((key, value) -> result.put(key, value.freeze()));
        return result;
    }

    private int coverageGain(
            PackedEvidence candidate,
            Set<UUID> coveredGoals,
            Set<RequirementKey> coveredRequirements
    ) {
        int newGoals = (int) candidate.goalIds().stream().filter(goal -> !coveredGoals.contains(goal)).count();
        int newRequirements = (int) candidate.requirementKeys().stream()
                .filter(requirement -> !coveredRequirements.contains(requirement)).count();
        return newGoals * 8 + newRequirements * 3;
    }

    private boolean betterCoverageCandidate(PackedEvidence candidate, PackedEvidence current) {
        return current == null || candidateOrder().compare(candidate, current) < 0;
    }

    private Comparator<PackedEvidence> candidateOrder() {
        return Comparator.comparingDouble(PackedEvidence::retrievalScore).reversed()
                .thenComparingInt(PackedEvidence::estimatedTokens)
                .thenComparing(value -> value.evidence().parentChunkId().toString());
    }

    public record Pack(List<PackedEvidence> evidence, int estimatedEvidenceTokens, int uniqueParentCount) {
        public Pack {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            if (estimatedEvidenceTokens < 0 || uniqueParentCount < evidence.size()) {
                throw new IllegalArgumentException("invalid final evidence pack metrics");
            }
        }
    }

    public record PackedEvidence(
            AcceptedEvidence evidence,
            Map<UUID, Set<UUID>> requirementIdsByGoal,
            double retrievalScore,
            int estimatedTokens
    ) {
        public PackedEvidence {
            if (evidence == null || requirementIdsByGoal == null || requirementIdsByGoal.isEmpty()) {
                throw new IllegalArgumentException("packed evidence must retain source and Goal coverage");
            }
            var copied = new LinkedHashMap<UUID, Set<UUID>>();
            requirementIdsByGoal.forEach((goal, requirements) -> copied.put(
                    goal, Set.copyOf(new LinkedHashSet<>(requirements))));
            requirementIdsByGoal = Map.copyOf(copied);
            if (!Double.isFinite(retrievalScore) || estimatedTokens < 1) {
                throw new IllegalArgumentException("invalid packed evidence score or token estimate");
            }
        }

        public Set<UUID> goalIds() {
            return requirementIdsByGoal.keySet();
        }

        public Set<RequirementKey> requirementKeys() {
            var result = new LinkedHashSet<RequirementKey>();
            requirementIdsByGoal.forEach((goal, requirements) -> requirements.forEach(
                    requirement -> result.add(new RequirementKey(goal, requirement))));
            return Set.copyOf(result);
        }
    }

    public record RequirementKey(UUID goalId, UUID requirementId) {
    }

    private record ParentKey(UUID documentVersionId, UUID parentChunkId) {
    }

    private static final class MutableParent {
        private AcceptedEvidence representative;
        private double retrievalScore;
        private final Map<UUID, LinkedHashSet<UUID>> requirementIdsByGoal = new LinkedHashMap<>();

        private MutableParent(AcceptedEvidence initial) {
            representative = initial;
            retrievalScore = initial.retrievalScore();
        }

        private void merge(AcceptedEvidence item) {
            if (!representative.documentVersionId().equals(item.documentVersionId())
                    || !representative.parentChunkId().equals(item.parentChunkId())) {
                throw new IllegalArgumentException("cannot merge different parent chunks");
            }
            if (!representative.quote().equals(item.quote())) {
                throw new IllegalStateException("the same parent chunk has inconsistent evidence text");
            }
            requirementIdsByGoal.computeIfAbsent(item.goalId(), ignored -> new LinkedHashSet<>())
                    .addAll(item.activeRequirementIds());
            if (item.retrievalScore() > retrievalScore) {
                representative = item;
                retrievalScore = item.retrievalScore();
            }
        }

        private PackedEvidence freeze() {
            var requirements = new LinkedHashMap<UUID, Set<UUID>>();
            requirementIdsByGoal.forEach((goal, values) -> requirements.put(goal, Set.copyOf(values)));
            int estimatedTokens = DeepModelInvoker.estimatedTokens(representative.quote())
                    + DeepModelInvoker.estimatedTokens(representative.titlePath())
                    + SERIALIZATION_TOKENS_PER_EVIDENCE;
            return new PackedEvidence(representative, requirements, retrievalScore, estimatedTokens);
        }
    }
}
