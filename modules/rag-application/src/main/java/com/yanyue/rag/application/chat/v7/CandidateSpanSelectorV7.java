package com.yanyue.rag.application.chat.v7;

import com.yanyue.rag.domain.chunking.v4.CandidateSpan;
import com.yanyue.rag.domain.chunking.v4.CandidateSpanBuilder;
import com.yanyue.rag.domain.chunking.v4.ParentContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Allocates the bounded Deep Read span budget across parent contexts. */
final class CandidateSpanSelectorV7 {
    private final CandidateSpanBuilder spanBuilder;

    CandidateSpanSelectorV7() {
        this(new CandidateSpanBuilder());
    }

    CandidateSpanSelectorV7(CandidateSpanBuilder spanBuilder) {
        this.spanBuilder = spanBuilder;
    }

    List<CandidateSpan> select(List<ParentContext> contexts, String focus, int maximum) {
        if (maximum <= 0 || contexts.isEmpty()) return List.of();
        var perParent = contexts.stream()
                .sorted(Comparator.comparingDouble(ParentContext::retrievalScore).reversed()
                        .thenComparing(value -> value.parentChunkId().toString()))
                .map(context -> spanBuilder.build(context, focus))
                .filter(spans -> !spans.isEmpty())
                .toList();
        if (perParent.isEmpty()) return List.of();

        var result = new ArrayList<CandidateSpan>();
        var seen = new LinkedHashSet<String>();
        int depth = 0;
        boolean added;
        do {
            added = false;
            for (var spans : perParent) {
                if (depth >= spans.size()) continue;
                var span = spans.get(depth);
                if (seen.add(span.spanId())) {
                    result.add(span);
                    added = true;
                    if (result.size() == maximum) return List.copyOf(result);
                }
            }
            depth++;
        } while (added);
        return List.copyOf(result);
    }
}
