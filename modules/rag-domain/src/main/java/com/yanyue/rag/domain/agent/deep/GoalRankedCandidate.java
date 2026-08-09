package com.yanyue.rag.domain.agent.deep;

import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.SearchMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record GoalRankedCandidate(
        UUID runId,
        UUID goalId,
        ResearchPhase phase,
        UUID chunkId,
        UUID documentId,
        UUID documentVersionId,
        Set<UUID> retrievalTaskIds,
        Set<SearchMode> retrievalSources,
        List<RouteObservation> routeObservations,
        int bestRawRank,
        double bestRawScore,
        int rrfRank,
        double rrfScore,
        Integer rerankRank,
        Double rerankScore,
        boolean rerankFallback,
        boolean selectedForParent
) {
    public GoalRankedCandidate {
        DeepValidation.required(runId, "runId");
        DeepValidation.required(goalId, "goalId");
        DeepValidation.required(phase, "phase");
        DeepValidation.required(chunkId, "chunkId");
        DeepValidation.required(documentId, "documentId");
        DeepValidation.required(documentVersionId, "documentVersionId");
        retrievalTaskIds = Set.copyOf(new LinkedHashSet<>(
                DeepValidation.required(retrievalTaskIds, "retrievalTaskIds")));
        retrievalSources = Set.copyOf(new LinkedHashSet<>(
                DeepValidation.required(retrievalSources, "retrievalSources")));
        routeObservations = List.copyOf(DeepValidation.required(routeObservations, "routeObservations"));
        var provenance = validateProvenance(routeObservations, retrievalTaskIds, retrievalSources);
        validateRawRanking(provenance, bestRawRank, bestRawScore);
        validateRrfRanking(rrfRank, rrfScore);
        validateRerank(rerankRank, rerankScore, rerankFallback, selectedForParent, rrfRank);
    }

    private static Provenance validateProvenance(
            List<RouteObservation> observations,
            Set<UUID> taskIds,
            Set<SearchMode> sources
    ) {
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("routeObservations must not be empty");
        }
        var observedQueryIds = new LinkedHashSet<UUID>();
        var observedModes = new LinkedHashSet<SearchMode>();
        for (var observation : observations) {
            if (!observedQueryIds.add(observation.queryId())) {
                throw new IllegalArgumentException("routeObservations must have unique query IDs");
            }
            observedModes.add(observation.searchMode());
        }
        if (!taskIds.equals(observedQueryIds) || !sources.equals(observedModes)) {
            throw new IllegalArgumentException("retrieval projections must exactly match route observations");
        }
        return new Provenance(
                observations.stream().mapToInt(RouteObservation::rawRank).min().orElseThrow(),
                observations.stream().mapToDouble(RouteObservation::rawScore).max().orElseThrow());
    }

    private static void validateRawRanking(Provenance provenance, int bestRawRank, double bestRawScore) {
        if (bestRawRank != provenance.bestRank() || Double.compare(bestRawScore, provenance.bestScore()) != 0) {
            throw new IllegalArgumentException("best raw rank and score must be derived from route observations");
        }
    }

    private static void validateRrfRanking(int rrfRank, double rrfScore) {
        // The final profile persists the expanded Top-80 per-Goal RRF set before reranking.
        DeepValidation.positiveAtMost(rrfRank, 80, "RRF rank");
        if (!Double.isFinite(rrfScore)) {
            throw new IllegalArgumentException("rrfScore must be finite");
        }
    }

    private static void validateRerank(
            Integer rerankRank,
            Double rerankScore,
            boolean rerankFallback,
            boolean selectedForParent,
            int rrfRank
    ) {
        if ((rerankRank == null) != (rerankScore == null)) {
            throw new IllegalArgumentException("rerank rank and score must either both be present or both be absent");
        }
        if (rerankRank != null) {
            // Final quality mode persists the expanded Top-14 rerank result.
            DeepValidation.positiveAtMost(rerankRank, 14, "rerank rank");
            if (!Double.isFinite(rerankScore)) {
                throw new IllegalArgumentException("rerankScore must be finite");
            }
        }
        if (rerankFallback && rerankRank != null) {
            throw new IllegalArgumentException("RRF fallback candidates must not claim a model rerank result");
        }
        if (selectedForParent && rerankRank == null && (!rerankFallback || rrfRank > 14)) {
            throw new IllegalArgumentException("only a post-rerank Top14 candidate can be selected for parent reading");
        }
    }

    private record Provenance(int bestRank, double bestScore) {
    }
}
