package com.yanyue.rag.domain.chunking;

import com.yanyue.rag.contract.parser.BlockType;
import com.yanyue.rag.domain.chunking.v4.ChunkSourceMap;
import com.yanyue.rag.domain.chunking.v4.ChunkSourceMapBuilder;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.SourceBlockSlice;
import com.yanyue.rag.domain.knowledge.Chunk;
import com.yanyue.rag.domain.knowledge.ChunkPolicy;
import com.yanyue.rag.domain.knowledge.ChunkType;
import com.yanyue.rag.domain.knowledge.DocumentBlock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AdaptiveParentChildChunker {
    public static final String TOKENIZER_NAME = "verityforge-lexical-estimator-v2";
    public static final String TOKEN_COUNT_METHOD = "ESTIMATED";

    private static final Pattern SENTENCE = Pattern.compile(".*?(?:[。！？!?；;]+(?:[\\\"'”’）)]*)|\\n+|$)", Pattern.DOTALL);
    private static final Pattern LINE = Pattern.compile("[^\\n]+(?:\\n|$)");

    private final TokenEstimator tokenEstimator;
    private final ChunkSourceMapBuilder sourceMapBuilder = new ChunkSourceMapBuilder();

    public AdaptiveParentChildChunker() {
        this(new TokenEstimator());
    }

    public AdaptiveParentChildChunker(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    public List<Chunk> chunk(UUID documentVersionId, List<DocumentBlock> input, ChunkPolicy policy) {
        return chunkWithSourceMaps(documentVersionId, input, policy).chunks();
    }

    public ChunkingResult chunkWithSourceMaps(
            UUID documentVersionId,
            List<DocumentBlock> input,
            ChunkPolicy policy
    ) {
        var blocks = input.stream()
                .filter(block -> block.text() != null && !block.text().isBlank())
                .filter(block -> block.type() != BlockType.PAGE_HEADER && block.type() != BlockType.PAGE_FOOTER)
                .sorted(Comparator.comparingInt(DocumentBlock::orderIndex))
                .toList();
        if (blocks.isEmpty()) return new ChunkingResult(List.of(), Map.of());

        var blocksById = blocks.stream().collect(java.util.stream.Collectors.toMap(
                DocumentBlock::id, value -> value, (left, right) -> left, LinkedHashMap::new));
        var sourceMaps = new LinkedHashMap<UUID, ChunkSourceMap>();
        var parents = buildParents(documentVersionId, blocks, policy, sourceMaps);
        var chunks = new ArrayList<Chunk>(parents.size() * 5);
        chunks.addAll(parents);

        int childOrder = 0;
        for (var parent : parents) {
            var parentMap = sourceMaps.get(parent.id());
            var parentSlices = slices(parentMap, blocksById);
            for (var childSlices : buildChildWindows(parentSlices, blocksById, policy)) {
                var mergedSlices = mergeAdjacent(childSlices);
                var text = text(mergedSlices);
                if (text.isBlank()) continue;
                var contextHeader = contextHeader(mergedSlices, blocksById);
                var renderMarkdown = renderMarkdown(mergedSlices, blocksById);
                var hash = sha256(contextHeader + "\0" + text + "\0" + renderMarkdown);
                var childId = stableId(documentVersionId, "child", childOrder, hash);
                var mapped = sourceMapBuilder.build(childId, mergedSlices);
                var child = new Chunk(
                        childId,
                        documentVersionId,
                        parent.id(),
                        ChunkType.CHILD,
                        childOrder++,
                        mapped.text(),
                        renderMarkdown,
                        contextHeader,
                        embeddingText(contextHeader, renderMarkdown),
                        tokenEstimator.estimate(mapped.text()),
                        TOKENIZER_NAME,
                        TOKEN_COUNT_METHOD,
                        sourceIds(mergedSlices),
                        hash,
                        policy.version(),
                        true
                );
                chunks.add(child);
                sourceMaps.put(child.id(), mapped.sourceMap());
            }
        }
        return new ChunkingResult(chunks, sourceMaps);
    }

    private List<Chunk> buildParents(
            UUID versionId,
            List<DocumentBlock> blocks,
            ChunkPolicy policy,
            Map<UUID, ChunkSourceMap> sourceMaps
    ) {
        var parents = new ArrayList<Chunk>();
        var blocksById = blocks.stream().collect(java.util.stream.Collectors.toMap(
                DocumentBlock::id, value -> value));
        var buffer = new ArrayList<SourceBlockSlice>();
        var previousPath = List.<String>of();
        boolean previousWasHeading = false;
        int tokens = 0;
        int parentOrder = 0;
        int minimumTokens = Math.min(policy.parentTargetTokens(), Math.max(
                policy.childTargetTokens() * 2,
                Math.round(policy.parentTargetTokens() * 0.6f)));

        for (var block : blocks) {
            int pieceMaximum = Math.max(policy.parentTargetTokens(),
                    policy.parentMaxTokens() - policy.parentOverlapTokens());
            var pieces = blockPieces(block, policy.parentTargetTokens(), pieceMaximum,
                    0);
            for (int pieceIndex = 0; pieceIndex < pieces.size(); pieceIndex++) {
                var piece = pieces.get(pieceIndex);
                boolean startsBlock = pieceIndex == 0;
                boolean heading = startsBlock && isHeading(block);
                boolean hardBoundary = !buffer.isEmpty() && heading && isHardHeading(block)
                        && hasSubstantiveContent(buffer, blocksById);
                boolean softBoundary = !buffer.isEmpty() && heading && isSoftHeading(block)
                        && tokens >= minimumTokens;
                boolean headingCapacityBoundary = !buffer.isEmpty() && heading
                        && tokens >= policy.parentMaxTokens() - policy.childTargetTokens();
                boolean sectionChanged = !buffer.isEmpty() && pieceIndex == 0
                        && !sameMajorSection(previousPath, block.headingPath())
                        && tokens >= minimumTokens;
                boolean overflow = !buffer.isEmpty() && tokens + piece.tokens() > policy.parentMaxTokens();
                boolean targetReached = !buffer.isEmpty() && tokens >= policy.parentTargetTokens()
                        && !previousWasHeading;
                if (hardBoundary || softBoundary || headingCapacityBoundary
                        || sectionChanged || overflow || targetReached) {
                    var previous = buffer;
                    parents.add(toParent(versionId, parentOrder++, buffer, blocks, policy, sourceMaps));
                    boolean semanticBoundary = hardBoundary || softBoundary || headingCapacityBoundary
                            || sectionChanged;
                    buffer = semanticBoundary
                            ? new ArrayList<>()
                            : new ArrayList<>(overlapTail(previous, policy.parentOverlapTokens(), blocksById));
                    tokens = tokenCount(buffer);
                    previousWasHeading = endsWithHeading(buffer, blocksById);
                    if (tokens + piece.tokens() > policy.parentMaxTokens()) {
                        buffer.clear();
                        tokens = 0;
                        previousWasHeading = false;
                    }
                }
                buffer.addAll(piece.slices());
                tokens += piece.tokens();
                previousPath = block.headingPath();
                previousWasHeading = isHeading(block);
            }
        }
        if (!buffer.isEmpty()) {
            parents.add(toParent(versionId, parentOrder, buffer, blocks, policy, sourceMaps));
        }
        return parents;
    }

    private Chunk toParent(
            UUID versionId,
            int order,
            List<SourceBlockSlice> rawSlices,
            List<DocumentBlock> blocks,
            ChunkPolicy policy,
            Map<UUID, ChunkSourceMap> sourceMaps
    ) {
        var slices = mergeAdjacent(rawSlices);
        var byId = blocks.stream().collect(java.util.stream.Collectors.toMap(DocumentBlock::id, value -> value));
        var contextHeader = contextHeader(slices, byId);
        var chunkText = text(slices);
        var renderMarkdown = renderMarkdown(slices, byId);
        var hash = sha256(contextHeader + "\0" + chunkText + "\0" + renderMarkdown);
        var id = stableId(versionId, "parent", order, hash);
        var mapped = sourceMapBuilder.build(id, slices);
        sourceMaps.put(id, mapped.sourceMap());
        return new Chunk(
                id,
                versionId,
                null,
                ChunkType.PARENT,
                order,
                mapped.text(),
                renderMarkdown,
                contextHeader,
                embeddingText(contextHeader, renderMarkdown),
                tokenEstimator.estimate(mapped.text()),
                TOKENIZER_NAME,
                TOKEN_COUNT_METHOD,
                sourceIds(slices),
                hash,
                policy.version(),
                true
        );
    }

    private List<BlockPiece> blockPieces(DocumentBlock block, int target, int maximum, int overlap) {
        var full = slice(block, 0, block.text().length());
        int tokens = tokenEstimator.estimate(block.text());
        if (tokens <= maximum) return List.of(new BlockPiece(List.of(full), tokens));
        return splitRanges(block.text(), block.type(), target, maximum, overlap).stream()
                .map(range -> {
                    var source = slice(block, range.start(), range.end());
                    return new BlockPiece(List.of(source), tokenEstimator.estimate(source.text()));
                }).toList();
    }

    private List<List<SourceBlockSlice>> buildChildWindows(
            List<SourceBlockSlice> parentSlices,
            Map<UUID, DocumentBlock> blocks,
            ChunkPolicy policy
    ) {
        var units = new ArrayList<SourceBlockSlice>();
        for (var parentSlice : parentSlices) {
            var block = blocks.get(parentSlice.documentBlockId());
            if (block == null) continue;
            var localText = parentSlice.text();
            for (var range : splitRanges(localText, block.type(), policy.childTargetTokens(),
                    policy.childMaxTokens(), 0)) {
                units.add(slice(block, parentSlice.blockLocalStart() + range.start(),
                        parentSlice.blockLocalStart() + range.end()));
            }
        }

        var windows = new ArrayList<List<SourceBlockSlice>>();
        var current = new ArrayList<SourceBlockSlice>();
        int tokens = 0;
        boolean hasNewContent = false;
        for (var unit : units) {
            int unitTokens = tokenEstimator.estimate(unit.text());
            if (!current.isEmpty() && tokens + unitTokens > policy.childMaxTokens()) {
                windows.add(List.copyOf(current));
                current = new ArrayList<>(overlapTail(current, policy.childOverlapTokens(), blocks));
                tokens = tokenCount(current);
                hasNewContent = false;
                if (!current.isEmpty() && tokens + unitTokens > policy.childMaxTokens()) {
                    current.clear();
                    tokens = 0;
                }
            }
            current.add(unit);
            tokens += unitTokens;
            hasNewContent = true;
            if (tokens >= policy.childTargetTokens()) {
                windows.add(List.copyOf(current));
                current = new ArrayList<>(overlapTail(current, policy.childOverlapTokens(), blocks));
                tokens = tokenCount(current);
                hasNewContent = false;
            }
        }
        if (hasNewContent && !current.isEmpty()) windows.add(List.copyOf(current));
        return mergeAbsorbableShortTail(windows, policy.childTargetTokens(), policy.childMaxTokens());
    }

    private List<List<SourceBlockSlice>> mergeAbsorbableShortTail(
            List<List<SourceBlockSlice>> windows,
            int targetTokens,
            int maximumTokens
    ) {
        if (windows.size() < 2) return windows;
        var last = mergeAdjacent(windows.getLast());
        if (tokenEstimator.estimate(text(last)) >= Math.max(1, Math.round(targetTokens * 0.48f))) {
            return windows;
        }
        var combined = new ArrayList<SourceBlockSlice>(windows.get(windows.size() - 2));
        combined.addAll(last);
        var merged = mergeAdjacent(combined);
        if (tokenEstimator.estimate(text(merged)) > maximumTokens) return windows;
        var result = new ArrayList<List<SourceBlockSlice>>(windows.subList(0, windows.size() - 2));
        result.add(merged);
        return result;
    }

    private List<Range> splitRanges(
            String text,
            BlockType type,
            int targetTokens,
            int maxTokens,
            int overlapTokens
    ) {
        if (tokenEstimator.estimate(text) <= maxTokens) return List.of(new Range(0, text.length()));
        var natural = naturalRanges(text, type);
        if (natural.size() <= 1) return tokenWindows(text, targetTokens, maxTokens, overlapTokens);

        var windows = new ArrayList<Range>();
        int start = -1;
        int end = -1;
        int tokens = 0;
        for (int rangeIndex = 0; rangeIndex < natural.size(); rangeIndex++) {
            var range = natural.get(rangeIndex);
            int rangeTokens = tokenEstimator.estimate(text.substring(range.start(), range.end()));
            if (rangeTokens > maxTokens) {
                if (start >= 0) windows.add(new Range(start, end));
                var nested = tokenWindows(text.substring(range.start(), range.end()), targetTokens, maxTokens,
                        overlapTokens);
                nested.forEach(value -> windows.add(new Range(range.start() + value.start(), range.start() + value.end())));
                start = -1;
                end = -1;
                tokens = 0;
                continue;
            }
            if (start >= 0 && tokens + rangeTokens > maxTokens) {
                windows.add(new Range(start, end));
                start = range.start();
                end = range.end();
                tokens = rangeTokens;
            } else {
                if (start < 0) start = range.start();
                end = range.end();
                tokens += rangeTokens;
            }
            boolean nextContinuesCode = rangeIndex + 1 < natural.size()
                    && isCodeContinuationStart(text, type, natural.get(rangeIndex + 1).start());
            if (tokens >= targetTokens && !nextContinuesCode) {
                windows.add(new Range(start, end));
                start = -1;
                end = -1;
                tokens = 0;
            }
        }
        if (start >= 0) windows.add(new Range(start, end));
        return windows;
    }

    private static boolean isCodeContinuationStart(String text, BlockType type, int start) {
        if (type != BlockType.CODE || start < 0 || start >= text.length()) return false;
        int end = text.indexOf('\n', start);
        var line = text.substring(start, end < 0 ? text.length() : end).stripLeading();
        if (line.isEmpty()) return false;
        if (")]},;".indexOf(line.charAt(0)) >= 0) return true;
        var upper = line.toUpperCase(java.util.Locale.ROOT);
        return upper.matches("^(?:AS\\s+\\$\\$|ELSE\\b|ELIF\\b|EXCEPT\\b|FINALLY\\b|CATCH\\b|"
                + "RETURN\\b|YIELD\\b|FROM\\b|WHERE\\b|GROUP\\s+BY\\b|ORDER\\s+BY\\b|HAVING\\b|"
                + "LIMIT\\b|ON\\b|AND\\b|OR\\b|VALUES\\b|SET\\b|JOIN\\b|LATERAL\\s+VIEW\\b|"
                + "DISTRIBUTED\\s+BY\\b|PROPERTIES\\b).*$");
    }

    private List<Range> naturalRanges(String text, BlockType type) {
        var matcher = switch (type) {
            case TABLE, CODE, LIST -> LINE.matcher(text);
            default -> SENTENCE.matcher(text);
        };
        var result = new ArrayList<Range>();
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            while (start < end && Character.isWhitespace(text.charAt(start))) start++;
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            if (end > start) result.add(new Range(start, end));
        }
        return result.isEmpty() ? List.of(new Range(0, text.length())) : mergeLeadingClosingRanges(text, result);
    }

    private static List<Range> mergeLeadingClosingRanges(String text, List<Range> ranges) {
        var merged = new ArrayList<Range>();
        for (var range : ranges) {
            if (!merged.isEmpty() && startsWithClosingToken(text, range.start(), range.end())) {
                var previous = merged.removeLast();
                merged.add(new Range(previous.start(), range.end()));
            } else {
                merged.add(range);
            }
        }
        return List.copyOf(merged);
    }

    private List<Range> tokenWindows(String text, int target, int maximum, int overlap) {
        var spans = tokenEstimator.spans(text);
        if (spans.isEmpty()) return List.of();
        var windows = new ArrayList<Range>();
        int startIndex = 0;
        while (startIndex < spans.size()) {
            var first = spans.get(startIndex);
            if (first.weight() > maximum) {
                windows.addAll(hardLexicalWindows(text, first.start(), first.end(), target, maximum, overlap));
                startIndex++;
                continue;
            }
            int endIndex = startIndex;
            int tokens = 0;
            while (endIndex < spans.size()) {
                int next = tokens + spans.get(endIndex).weight();
                if (next > maximum && endIndex > startIndex) break;
                if (endIndex > startIndex && endIndex + 1 < spans.size()
                        && next + spans.get(endIndex + 1).weight() > maximum
                        && startsWithClosingToken(text, spans.get(endIndex + 1).start(),
                                spans.get(endIndex + 1).end())) {
                    break;
                }
                tokens = next;
                endIndex++;
                if (tokens >= target) {
                    while (endIndex < spans.size()
                            && startsWithClosingToken(text, spans.get(endIndex).start(), spans.get(endIndex).end())
                            && tokens + spans.get(endIndex).weight() <= maximum) {
                        tokens += spans.get(endIndex).weight();
                        endIndex++;
                    }
                    break;
                }
                if (tokens >= maximum) break;
            }
            if (endIndex == startIndex) endIndex++;
            endIndex = moveClosingTokenBoundaryLeft(text, spans, startIndex, endIndex);
            windows.add(new Range(spans.get(startIndex).start(), spans.get(endIndex - 1).end()));
            if (endIndex >= spans.size()) break;
            int overlapWeight = 0;
            int nextStart = endIndex;
            while (nextStart > startIndex && overlapWeight < overlap) {
                nextStart--;
                overlapWeight += spans.get(nextStart).weight();
            }
            startIndex = Math.max(startIndex + 1, nextStart);
        }
        return windows;
    }

    private static int moveClosingTokenBoundaryLeft(
            String text,
            List<TokenEstimator.TokenSpan> spans,
            int startIndex,
            int endIndex
    ) {
        if (endIndex >= spans.size() || endIndex - startIndex <= 1
                || !startsWithClosingToken(text, spans.get(endIndex).start(), spans.get(endIndex).end())) {
            return endIndex;
        }
        // Keep a lexical token with punctuation that could not fit in the preceding window.
        return endIndex - 1;
    }

    private static boolean startsWithClosingToken(String text, int start, int end) {
        if (start < 0 || start >= end || end > text.length()) return false;
        var value = text.substring(start, end).stripLeading();
        if (value.isEmpty()) return false;
        int character = value.codePointAt(0);
        int type = Character.getType(character);
        return "，。；：、！？,.;:!?)}]".indexOf(character) >= 0
                || type == Character.END_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION;
    }

    private List<Range> hardLexicalWindows(
            String text,
            int start,
            int end,
            int targetTokens,
            int maximumTokens,
            int overlapTokens
    ) {
        var windows = new ArrayList<Range>();
        int cursor = start;
        int targetCharacters = Math.max(1, targetTokens * 4);
        int maximumCharacters = Math.max(targetCharacters, maximumTokens * 4);
        int overlapCharacters = Math.max(0, overlapTokens * 4);
        while (cursor < end) {
            int remainingCodePoints = text.codePointCount(cursor, end);
            int width = Math.min(maximumCharacters, remainingCodePoints);
            int windowEnd = text.offsetByCodePoints(cursor, width);
            windows.add(new Range(cursor, windowEnd));
            if (windowEnd >= end) break;
            int advance = Math.max(1, Math.min(targetCharacters, width) - overlapCharacters);
            cursor = text.offsetByCodePoints(cursor, advance);
        }
        return windows;
    }

    private List<SourceBlockSlice> overlapTail(
            List<SourceBlockSlice> slices,
            int overlapTokens,
            Map<UUID, DocumentBlock> blocks
    ) {
        if (overlapTokens <= 0 || slices.isEmpty()) return List.of();
        var result = new ArrayList<SourceBlockSlice>();
        int tokens = 0;
        for (int index = slices.size() - 1; index >= 0 && tokens < overlapTokens; index--) {
            var slice = slices.get(index);
            int sliceTokens = tokenEstimator.estimate(slice.text());
            int remaining = overlapTokens - tokens;
            if (sliceTokens <= remaining) {
                result.addFirst(slice);
                tokens += sliceTokens;
            } else {
                var block = blocks.get(slice.documentBlockId());
                var tail = tailSlice(slice, remaining, block == null ? BlockType.PARAGRAPH : block.type());
                if (tail != null) result.addFirst(tail);
                break;
            }
        }
        return List.copyOf(result);
    }

    private SourceBlockSlice tailSlice(SourceBlockSlice slice, int tokenBudget, BlockType type) {
        if (tokenBudget <= 0) return null;
        var ranges = naturalRanges(slice.text(), type);
        if (ranges.isEmpty()) return null;
        int tokens = 0;
        int start = slice.text().length();
        int startRangeIndex = ranges.size();
        for (int index = ranges.size() - 1; index >= 0; index--) {
            var range = ranges.get(index);
            int rangeTokens = tokenEstimator.estimate(slice.text().substring(range.start(), range.end()));
            if (rangeTokens > tokenBudget - tokens) break;
            start = range.start();
            startRangeIndex = index;
            tokens += rangeTokens;
        }
        if (tokens == 0) {
            var last = ranges.getLast();
            int lastTokens = tokenEstimator.estimate(slice.text().substring(last.start(), last.end()));
            if (lastTokens > tokenBudget * 2) return null;
            start = last.start();
            startRangeIndex = ranges.size() - 1;
            tokens = lastTokens;
        }
        while (startRangeIndex < ranges.size()
                && isCodeContinuationStart(slice.text(), type, ranges.get(startRangeIndex).start())) {
            startRangeIndex++;
        }
        if (startRangeIndex >= ranges.size()) return null;
        start = ranges.get(startRangeIndex).start();
        if (start >= slice.text().length()) return null;
        return new SourceBlockSlice(slice.documentBlockId(), slice.blockText(),
                slice.blockLocalStart() + start, slice.blockLocalEnd(),
                slice.documentSourceStart(), slice.documentSourceEnd(),
                slice.documentOffsetUnit(), slice.pageNumber());
    }

    private int tokenCount(List<SourceBlockSlice> slices) {
        return tokenEstimator.estimate(text(mergeAdjacent(slices)));
    }

    private static boolean sentenceBoundary(String text, int end) {
        if (end <= 0 || end > text.length()) return false;
        return ".!?。！？;；\n".indexOf(text.charAt(end - 1)) >= 0;
    }

    private static boolean isHeading(DocumentBlock block) {
        return block.type() == BlockType.TITLE || block.type() == BlockType.HEADING;
    }

    private static boolean isHardHeading(DocumentBlock block) {
        if (block.type() == BlockType.TITLE) return true;
        return headingLevel(block) == 1;
    }

    private static boolean isSoftHeading(DocumentBlock block) {
        return headingLevel(block) == 2;
    }

    private static int headingLevel(DocumentBlock block) {
        if (block.type() == BlockType.TITLE) return 1;
        if (block.type() != BlockType.HEADING) return Integer.MAX_VALUE;
        var value = block.attributes().get("headingLevel");
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? block.headingPath().size() : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return block.headingPath().size();
        }
    }

    private static boolean hasSubstantiveContent(
            List<SourceBlockSlice> slices,
            Map<UUID, DocumentBlock> blocks
    ) {
        return slices.stream().map(SourceBlockSlice::documentBlockId).map(blocks::get)
                .filter(java.util.Objects::nonNull).anyMatch(block -> !isHeading(block));
    }

    private static boolean endsWithHeading(
            List<SourceBlockSlice> slices,
            Map<UUID, DocumentBlock> blocks
    ) {
        if (slices.isEmpty()) return false;
        var block = blocks.get(slices.getLast().documentBlockId());
        return block != null && isHeading(block);
    }

    private static boolean sameMajorSection(List<String> left, List<String> right) {
        return left.stream().limit(2).toList().equals(right.stream().limit(2).toList());
    }

    private static SourceBlockSlice slice(DocumentBlock block, int start, int end) {
        return new SourceBlockSlice(block.id(), block.text(), start, end, block.sourceStart(), block.sourceEnd(),
                block.sourceOffsetUnit() == null ? OffsetUnit.UTF16_CODE_UNIT : block.sourceOffsetUnit(),
                block.pageNumber());
    }

    private static List<SourceBlockSlice> slices(
            ChunkSourceMap sourceMap,
            Map<UUID, DocumentBlock> blocks
    ) {
        if (sourceMap == null) return List.of();
        return sourceMap.segments().stream().map(segment -> {
            var block = blocks.get(segment.documentBlockId());
            if (block == null) return null;
            return slice(block, segment.blockLocalStart(), segment.blockLocalEnd());
        }).filter(java.util.Objects::nonNull).toList();
    }

    private static List<SourceBlockSlice> mergeAdjacent(List<SourceBlockSlice> slices) {
        var result = new ArrayList<SourceBlockSlice>();
        for (var slice : slices) {
            if (!result.isEmpty()) {
                var previous = result.getLast();
                if (previous.documentBlockId().equals(slice.documentBlockId())
                        && slice.blockLocalStart() <= previous.blockLocalEnd()) {
                    result.set(result.size() - 1, new SourceBlockSlice(
                            previous.documentBlockId(), previous.blockText(), previous.blockLocalStart(),
                            Math.max(previous.blockLocalEnd(), slice.blockLocalEnd()),
                            previous.documentSourceStart(), previous.documentSourceEnd(),
                            previous.documentOffsetUnit(), previous.pageNumber()));
                    continue;
                }
            }
            result.add(slice);
        }
        return List.copyOf(result);
    }

    private static String text(List<SourceBlockSlice> slices) {
        return slices.stream().map(SourceBlockSlice::text).map(String::strip)
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private static List<UUID> sourceIds(List<SourceBlockSlice> slices) {
        return slices.stream().map(SourceBlockSlice::documentBlockId).collect(
                java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    private static String contextHeader(
            List<SourceBlockSlice> slices,
            Map<UUID, DocumentBlock> blocks
    ) {
        return slices.stream().map(SourceBlockSlice::documentBlockId).map(blocks::get)
                .filter(java.util.Objects::nonNull).map(DocumentBlock::headingPath)
                .filter(path -> path != null && !path.isEmpty()).findFirst()
                .map(path -> String.join(" > ", path)).orElse("");
    }

    private static String embeddingText(String contextHeader, String text) {
        return contextHeader == null || contextHeader.isBlank()
                ? text
                : "Section: " + contextHeader + "\n\n" + text;
    }

    private static String renderMarkdown(
            List<SourceBlockSlice> slices,
            Map<UUID, DocumentBlock> blocks
    ) {
        return slices.stream().map(slice -> renderSlice(slice, blocks.get(slice.documentBlockId())))
                .map(String::strip).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private static String renderSlice(SourceBlockSlice slice, DocumentBlock block) {
        var value = slice.text();
        if (block == null) return value;
        return switch (block.type()) {
            case TITLE, HEADING -> "#".repeat(Math.max(1, Math.min(6, headingLevel(block))))
                    + " " + value.strip();
            case CODE -> fencedCode(value, block.attributes().get("language"));
            case TABLE -> renderTableSlice(slice, block);
            case LIST -> renderListSlice(value, block.attributes());
            default -> value;
        };
    }

    private static String renderListSlice(String value, Map<String, Object> attributes) {
        var lines = value.lines().filter(line -> !line.isBlank()).toList();
        if ("FEATURE_GATE_LIST".equals(attributes.get("structureDetected"))) {
            return lines.stream().map(line -> line.strip().matches(
                            "(?i)^(?:kube:)?[A-Za-z][A-Za-z0-9_.:-]*="
                                    + "(?:true|false)\\|(?:true|false)\\s*\\(.*")
                    ? "- " + line.strip()
                    : line.strip()).collect(java.util.stream.Collectors.joining("\n"));
        }
        boolean hasMarker = lines.stream().anyMatch(line ->
                line.matches("^\\s*(?:[-+*•]|\\d+[.)])\\s+.*"));
        return lines.stream()
                .map(line -> hasMarker
                        ? line.replaceFirst("^(\\s*)•\\s+", "$1- ")
                        : "- " + line.strip())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String fencedCode(String value, Object languageValue) {
        var language = languageValue == null ? "" : languageValue.toString().strip();
        if (!language.matches("[A-Za-z0-9_+.-]+")) language = "";
        int fenceLength = 3;
        var matcher = Pattern.compile("`+").matcher(value);
        while (matcher.find()) fenceLength = Math.max(fenceLength, matcher.group().length() + 1);
        var fence = "`".repeat(fenceLength);
        return fence + language + "\n" + value.strip() + "\n" + fence;
    }

    private static String renderTableSlice(SourceBlockSlice slice, DocumentBlock block) {
        var value = slice.text().strip();
        if (slice.blockLocalStart() == 0) return value;
        var lines = block.text().lines().toList();
        if (lines.size() < 2 || !isMarkdownTableSeparator(lines.get(1))) return value;
        var header = lines.get(0).strip() + "\n" + lines.get(1).strip();
        return value.startsWith(header) ? value : header + "\n" + value;
    }

    private static boolean isMarkdownTableSeparator(String line) {
        var cells = line.strip().replaceFirst("^\\|", "").replaceFirst("\\|$", "").split("\\|");
        return cells.length > 0 && java.util.Arrays.stream(cells)
                .allMatch(cell -> cell.strip().matches(":?-{3,}:?"));
    }

    private static UUID stableId(UUID versionId, String type, int order, String hash) {
        var value = versionId + ":" + type + ":" + order + ":" + hash;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record BlockPiece(List<SourceBlockSlice> slices, int tokens) { }
    private record Range(int start, int end) { }
}
