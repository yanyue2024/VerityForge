package com.yanyue.rag.domain.chunking;

import com.yanyue.rag.domain.chunking.TokenEstimator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class CandidateSpanBuilder {
    public static final int MAX_SPANS_PER_PARENT = 16;
    public static final int MAX_SPAN_TOKENS = 600;

    private static final Pattern FOCUS_TERM = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]|[\\p{L}\\p{N}_-]+"
    );
    private static final Set<Character> SENTENCE_END = Set.of(
            '.', '!', '?', ';', '。', '！', '？', '；'
    );

    private final TokenEstimator tokenEstimator;

    public CandidateSpanBuilder() {
        this(new TokenEstimator());
    }

    public CandidateSpanBuilder(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    public List<CandidateSpan> build(ParentContext context, String focusText) {
        if (context.sourceMap().status() == SourceMapStatus.UNMAPPABLE || context.text().isBlank()) {
            return List.of();
        }
        var focusTerms = focusTerms(focusText);
        var parentHash = sha256(context.text());
        var candidates = new ArrayList<CandidateSpan>();
        for (var segment : context.sourceMap().segments()) {
            for (var range : splitSegment(context.text(), segment)) {
                toCandidate(context, range, focusTerms, parentHash).ifPresent(candidates::add);
            }
        }
        candidates.sort(candidateOrder());
        return List.copyOf(candidates.subList(0, Math.min(MAX_SPANS_PER_PARENT, candidates.size())));
    }

    private java.util.Optional<CandidateSpan> toCandidate(
            ParentContext context,
            Range range,
            Set<String> focusTerms,
            String parentHash
    ) {
        var text = context.text().substring(range.start(), range.end());
        int estimatedTokens = tokenEstimator.estimate(text);
        if (estimatedTokens == 0 || estimatedTokens > MAX_SPAN_TOKENS) return java.util.Optional.empty();
        return context.sourceMap().anchorFor(context.documentVersionId(), range.start(), range.end())
                .map(anchor -> new CandidateSpan(
                        spanId(context, range, parentHash),
                        context.parentChunkId(),
                        range.start(),
                        range.end(),
                        text,
                        context.titlePath(),
                        anchor,
                        estimatedTokens,
                        anchorDistance(context.childAnchors(), range),
                        relevance(text, focusTerms)
                ));
    }

    private List<Range> splitSegment(String parentText, ChunkSourceSegment segment) {
        var ranges = new ArrayList<Range>();
        int start = segment.chunkLocalStart();
        while (start < segment.chunkLocalEnd()) {
            int tokenEnd = maximumTokenEnd(parentText, start, segment.chunkLocalEnd());
            int end = chooseBoundary(parentText, start, tokenEnd, segment.chunkLocalEnd());
            if (end <= start) end = safeUtf16Boundary(parentText, Math.min(segment.chunkLocalEnd(), start + 1));
            ranges.add(new Range(start, end));
            start = end;
        }
        return ranges;
    }

    private int maximumTokenEnd(String text, int start, int segmentEnd) {
        if (tokenEstimator.estimate(text.substring(start, segmentEnd)) <= MAX_SPAN_TOKENS) return segmentEnd;
        int low = start + 1;
        int high = segmentEnd;
        int best = low;
        while (low <= high) {
            int rawMiddle = low + (high - low) / 2;
            int middle = safeUtf16Boundary(text, rawMiddle);
            if (middle <= start) middle = Math.min(segmentEnd, start + 1);
            int tokens = tokenEstimator.estimate(text.substring(start, middle));
            if (tokens <= MAX_SPAN_TOKENS) {
                best = middle;
                low = rawMiddle + 1;
            } else {
                high = rawMiddle - 1;
            }
        }
        return safeUtf16Boundary(text, best);
    }

    private int chooseBoundary(String text, int start, int tokenEnd, int segmentEnd) {
        if (tokenEnd >= segmentEnd) return segmentEnd;
        int minimum = start + Math.max(1, (tokenEnd - start) / 3);
        int paragraph = lastBoundary(text, "\n\n", minimum, tokenEnd);
        if (paragraph > start) return paragraph;
        int line = lastBoundary(text, "\n", minimum, tokenEnd);
        if (line > start) return line;
        int sentence = lastSentenceBoundary(text, minimum, tokenEnd);
        return sentence > start ? sentence : safeUtf16Boundary(text, tokenEnd);
    }

    private int lastBoundary(String text, String delimiter, int minimum, int maximum) {
        int index = text.lastIndexOf(delimiter, maximum - delimiter.length());
        int boundary = index < 0 ? -1 : index + delimiter.length();
        return boundary >= minimum ? safeUtf16Boundary(text, boundary) : -1;
    }

    private int lastSentenceBoundary(String text, int minimum, int maximum) {
        for (int index = maximum - 1; index >= minimum; index--) {
            if (SENTENCE_END.contains(text.charAt(index))) return safeUtf16Boundary(text, index + 1);
        }
        return -1;
    }

    private static int safeUtf16Boundary(String text, int boundary) {
        if (boundary > 0 && boundary < text.length()
                && Character.isHighSurrogate(text.charAt(boundary - 1))
                && Character.isLowSurrogate(text.charAt(boundary))) {
            return boundary - 1;
        }
        return boundary;
    }

    private static Comparator<CandidateSpan> candidateOrder() {
        return Comparator.comparingInt(CandidateSpan::childAnchorDistance)
                .thenComparing(Comparator.comparingInt(CandidateSpan::localRelevanceScore).reversed())
                .thenComparingInt(CandidateSpan::localStart);
    }

    private static int anchorDistance(List<ChildAnchor> anchors, Range range) {
        return anchors.stream().mapToInt(anchor -> anchor.distanceTo(range.start(), range.end()))
                .min().orElse(Integer.MAX_VALUE);
    }

    private static Set<String> focusTerms(String focusText) {
        if (focusText == null || focusText.isBlank()) return Set.of();
        var terms = new HashSet<String>();
        var matcher = FOCUS_TERM.matcher(focusText.toLowerCase(Locale.ROOT));
        while (matcher.find()) terms.add(matcher.group());
        return Set.copyOf(terms);
    }

    private static int relevance(String text, Set<String> terms) {
        var normalized = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (var term : terms) {
            int offset = 0;
            while ((offset = normalized.indexOf(term, offset)) >= 0) {
                score++;
                offset += Math.max(1, term.length());
            }
        }
        return score;
    }

    private static String spanId(ParentContext context, Range range, String parentHash) {
        return stableSpanId(context.documentVersionId(), context.parentChunkId(), range.start(), range.end(),
                parentHash);
    }

    public static String stableSpanId(
            java.util.UUID documentVersionId,
            java.util.UUID parentChunkId,
            int localStart,
            int localEnd,
            String parentTextHash
    ) {
        return sha256(documentVersionId + "\u001f" + parentChunkId + "\u001f"
                + localStart + "\u001f" + localEnd + "\u001f" + parentTextHash);
    }

    public static String textHash(String value) {
        return sha256(value);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record Range(int start, int end) {
    }
}
