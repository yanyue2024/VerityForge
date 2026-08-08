package com.yanyue.rag.application.chat;

import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

final class ContextPackBuilder {
    List<PackedEvidence> build(List<RetrievalHit> expanded, int tokenBudget) {
        var result = new ArrayList<PackedEvidence>();
        var seenChunks = new HashSet<java.util.UUID>();
        var seenTexts = new HashSet<String>();
        int used = 0;
        for (var hit : expanded) {
            var normalized = hit.text().strip().replaceAll("\\s+", " ");
            if (normalized.isBlank() || !seenChunks.add(hit.chunkId())) continue;
            var fingerprint = normalized.toLowerCase(Locale.ROOT);
            if (!seenTexts.add(fingerprint)) continue;
            var tokens = estimateTokens(normalized);
            if (!result.isEmpty() && used + tokens > tokenBudget) continue;
            if (result.isEmpty() && tokens > tokenBudget) {
                var maximumCharacters = Math.max(400, tokenBudget * 2);
                normalized = normalized.substring(0, Math.min(normalized.length(), maximumCharacters));
                tokens = estimateTokens(normalized);
                hit = hit.withText(normalized);
            }
            result.add(new PackedEvidence("E" + (result.size() + 1), hit, tokens));
            used += tokens;
        }
        return List.copyOf(result);
    }

    private int estimateTokens(String text) {
        long cjk = text.codePoints().filter(value -> value >= 0x3400 && value <= 0x9fff).count();
        long other = text.codePointCount(0, text.length()) - cjk;
        return Math.max(1, Math.toIntExact(cjk + (other + 3) / 4));
    }

    record PackedEvidence(String evidenceId, RetrievalHit hit, int estimatedTokens) {
    }
}
