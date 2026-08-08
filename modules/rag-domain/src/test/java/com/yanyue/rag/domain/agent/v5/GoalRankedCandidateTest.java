package com.yanyue.rag.domain.agent.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoalRankedCandidateTest {
    @Test
    void routeObservationsAreTheOnlySourceOfQueryAndModeProjections() {
        var keywordQueryId = UUID.randomUUID();
        var semanticQueryId = UUID.randomUUID();
        var observations = List.of(
                new RouteObservation(keywordQueryId, SearchMode.KEYWORD, 2, 0.8),
                new RouteObservation(semanticQueryId, SearchMode.SEMANTIC, 5, 0.7));

        var candidate = candidate(observations, Set.of(keywordQueryId, semanticQueryId),
                Set.of(SearchMode.KEYWORD, SearchMode.SEMANTIC), 2, 0.8);

        assertEquals(2, candidate.routeObservations().size());
        assertEquals(Set.of(keywordQueryId, semanticQueryId), candidate.retrievalTaskIds());
    }

    @Test
    void rejectsMissingOrInventedRouteProvenance() {
        var queryId = UUID.randomUUID();
        var observations = List.of(new RouteObservation(queryId, SearchMode.KEYWORD, 1, 0.9));

        assertThrows(IllegalArgumentException.class, () -> candidate(
                List.of(), Set.of(), Set.of(), 1, 0.9));
        assertThrows(IllegalArgumentException.class, () -> candidate(
                observations, Set.of(queryId, UUID.randomUUID()), Set.of(SearchMode.KEYWORD), 1, 0.9));
        assertThrows(IllegalArgumentException.class, () -> candidate(
                observations, Set.of(queryId), Set.of(SearchMode.KEYWORD, SearchMode.SEMANTIC), 1, 0.9));
        assertThrows(IllegalArgumentException.class, () -> candidate(
                observations, Set.of(queryId), Set.of(SearchMode.KEYWORD), 2, 0.9));
    }

    @Test
    void rerankFieldsMustBeCompleteAndFinite() {
        var queryId = UUID.randomUUID();
        var observation = new RouteObservation(queryId, SearchMode.KEYWORD, 1, 0.9);

        assertThrows(IllegalArgumentException.class, () -> new GoalRankedCandidate(
                UUID.randomUUID(), UUID.randomUUID(), ResearchPhase.PRIMARY, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(queryId), Set.of(SearchMode.KEYWORD), List.of(observation),
                1, 0.9, 1, 0.02, 1, null, false, false));
    }

    private GoalRankedCandidate candidate(
            List<RouteObservation> observations,
            Set<UUID> taskIds,
            Set<SearchMode> sources,
            int bestRawRank,
            double bestRawScore
    ) {
        return new GoalRankedCandidate(UUID.randomUUID(), UUID.randomUUID(), ResearchPhase.PRIMARY,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), taskIds, sources, observations,
                bestRawRank, bestRawScore, 1, 0.02, 1, 0.95, false, true);
    }
}
