package com.yanyue.rag.domain.chunking;

import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

public final class TokenEstimator {
    private static final Pattern TOKEN = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]|[\\p{L}\\p{N}_-]+|[^\\s]"
    );

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return spans(text).stream().mapToInt(TokenSpan::weight).sum();
    }

    public List<TokenSpan> spans(String text) {
        if (text == null || text.isBlank()) return List.of();
        var result = new ArrayList<TokenSpan>();
        var matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            var value = matcher.group();
            int codePoints = value.codePointCount(0, value.length());
            boolean lexical = value.codePoints().allMatch(character -> Character.isLetterOrDigit(character)
                    || character == '_' || character == '-');
            int weight = lexical ? Math.max(1, (codePoints + 3) / 4) : 1;
            result.add(new TokenSpan(matcher.start(), matcher.end(), weight));
        }
        return List.copyOf(result);
    }

    public record TokenSpan(int start, int end, int weight) { }
}
