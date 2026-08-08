package com.yanyue.rag.application.chat;

import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() {
    }

    public static List<RetrievalHit> fuse(List<List<RetrievalHit>> rankings, int limit) {
        var scores = new LinkedHashMap<UUID, Double>();
        var hits = new LinkedHashMap<UUID, RetrievalHit>();
        for (var ranking : rankings) {
            for (int index = 0; index < ranking.size(); index++) {
                var hit = ranking.get(index);
                hits.merge(hit.chunkId(), hit, (existing, duplicate) -> existing.withScore(
                        Math.max(existing.score(), duplicate.score()),
                        mergeSources(existing.sources(), duplicate.sources())));
                scores.merge(hit.chunkId(), 1.0 / (60 + index + 1), Double::sum);
            }
        }
        var ordered = new ArrayList<>(hits.values());
        ordered.sort(Comparator.comparingDouble((RetrievalHit hit) -> scores.get(hit.chunkId())).reversed());
        return ordered.stream().limit(limit)
                .map(hit -> hit.withScore(scores.get(hit.chunkId()), mergeSources(hit.sources(), "rrf")))
                .toList();
    }

    private static List<String> mergeSources(List<String> current, String source) {
        var values = new ArrayList<>(current);
        if (!values.contains(source)) values.add(source);
        return List.copyOf(values);
    }

    private static List<String> mergeSources(List<String> left, List<String> right) {
        var values = new ArrayList<>(left);
        for (var source : right) if (!values.contains(source)) values.add(source);
        return List.copyOf(values);
    }
}
