package com.yanyue.rag.application.chat;

import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class AgentCandidateGate {
    List<RetrievalHit> select(
            List<RetrievalHit> candidates,
            List<RerankModelPort.RerankScore> scores,
            double minimumScore
    ) {
        var ranked = new ArrayList<RetrievalHit>();
        var seenIndexes = new HashSet<Integer>();
        for (var score : scores.stream()
                .sorted(java.util.Comparator.comparingDouble(RerankModelPort.RerankScore::score).reversed())
                .toList()) {
            if (score.index() < 0 || score.index() >= candidates.size() || !seenIndexes.add(score.index())) {
                throw new IllegalStateException("Rerank model returned an invalid candidate index");
            }
            var hit = candidates.get(score.index());
            ranked.add(hit.withScore(score.score(), append(hit.sources(), "rerank")));
        }
        var accepted = ranked.stream().filter(hit -> hit.score() >= minimumScore).toList();
        if (!accepted.isEmpty() || ranked.isEmpty()) return accepted;
        var fallback = ranked.getFirst();
        return List.of(fallback.withScore(
                fallback.score(), append(fallback.sources(), "rerank-threshold-fallback")));
    }

    private List<String> append(List<String> values, String value) {
        var copy = new ArrayList<>(values);
        if (!copy.contains(value)) copy.add(value);
        return List.copyOf(copy);
    }
}
