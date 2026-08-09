package com.yanyue.rag.domain.chunking;

import com.yanyue.rag.contract.parser.BlockType;
import com.yanyue.rag.domain.chunking.SourceMapStatus;
import com.yanyue.rag.domain.knowledge.Chunk;
import com.yanyue.rag.domain.knowledge.ChunkPolicy;
import com.yanyue.rag.domain.knowledge.ChunkType;
import com.yanyue.rag.domain.knowledge.DocumentBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Evaluates retrieval chunks separately from parser fidelity. */
public final class ChunkQualityAssessor {
    public ChunkQualityReport assess(ChunkingResult result, List<DocumentBlock> blocks, ChunkPolicy policy) {
        var chunks = result.chunks();
        var parents = chunks.stream().filter(value -> value.type() == ChunkType.PARENT).toList();
        var children = chunks.stream().filter(value -> value.type() == ChunkType.CHILD).toList();
        var blocksById = blocks.stream().collect(Collectors.toMap(DocumentBlock::id, value -> value));
        var parentById = parents.stream().collect(Collectors.toMap(Chunk::id, value -> value));
        var childrenByParent = children.stream().collect(Collectors.groupingBy(Chunk::parentChunkId));

        var unmapped = chunks.stream()
                .filter(value -> result.sourceMaps().get(value.id()) == null
                        || result.sourceMaps().get(value.id()).status() != SourceMapStatus.MAPPED)
                .toList();
        var overLimit = chunks.stream()
                .filter(value -> value.estimatedTokens() > (value.type() == ChunkType.PARENT
                        ? policy.parentMaxTokens() : policy.childMaxTokens()))
                .toList();
        var orphanChildren = children.stream().filter(value -> !parentById.containsKey(value.parentChunkId())).toList();
        var headingOnlyChildren = children.stream().filter(value -> isHeadingOnly(value, blocksById)).toList();
        var oneChildParents = parents.stream()
                .filter(value -> childrenByParent.getOrDefault(value.id(), List.of()).size() == 1)
                .toList();
        var identicalSingleChildParents = oneChildParents.stream().filter(parent -> {
            var child = childrenByParent.get(parent.id()).getFirst();
            return parent.text().strip().equals(child.text().strip());
        }).toList();
        var emptyRenderChunks = chunks.stream()
                .filter(value -> value.renderMarkdown() == null || value.renderMarkdown().isBlank()).toList();
        var anchorContaminatedChunks = chunks.stream()
                .filter(value -> value.contextHeader().matches("(?s).*\\{#[\\w:.-]+}.*"))
                .toList();
        var unfencedCodeChunks = chunks.stream().filter(value -> containsType(value, blocksById, BlockType.CODE))
                .filter(value -> !value.renderMarkdown().contains("```") && !value.renderMarkdown().contains("~~~"))
                .toList();
        var missingTableHeaderChunks = chunks.stream()
                .filter(value -> missesVirtualTableHeader(value, result, blocksById)).toList();
        var suspiciousStarts = children.stream()
                .filter(value -> startsSuspiciously(value.text()))
                .toList();

        int shortChildThreshold = Math.max(1, (int) Math.round(policy.childTargetTokens() * 0.48));
        int shortParentThreshold = Math.max(policy.childTargetTokens(),
                (int) Math.round(policy.parentTargetTokens() * 0.40));
        var shortChildren = children.stream()
                .filter(value -> value.estimatedTokens() < shortChildThreshold)
                .toList();
        var shortParents = parents.stream()
                .filter(value -> value.estimatedTokens() < shortParentThreshold)
                .toList();

        var issues = new ArrayList<ChunkQualityIssue>();
        if (parents.isEmpty() || children.isEmpty()) {
            issues.add(issue("EMPTY_CHUNK_SET", ChunkQualityStatus.FAIL,
                    "Chunking must produce at least one parent and one child", chunks));
        }
        if (!unmapped.isEmpty()) {
            issues.add(issue("UNMAPPED_CHUNKS", ChunkQualityStatus.FAIL,
                    "Every parent and child chunk must retain a direct source map", unmapped));
        }
        if (!overLimit.isEmpty()) {
            issues.add(issue("TOKEN_HARD_LIMIT_EXCEEDED", ChunkQualityStatus.FAIL,
                    "Chunk token hard limits must never be exceeded", overLimit));
        }
        if (!orphanChildren.isEmpty()) {
            issues.add(issue("ORPHAN_CHILDREN", ChunkQualityStatus.FAIL,
                    "Every child chunk must reference a parent from the same result", orphanChildren));
        }
        if (!emptyRenderChunks.isEmpty()) {
            issues.add(issue("EMPTY_RENDER_CONTENT", ChunkQualityStatus.FAIL,
                    "Every chunk must have independently readable render content", emptyRenderChunks));
        }
        if (!unfencedCodeChunks.isEmpty()) {
            issues.add(issue("UNFENCED_CODE_RENDER", ChunkQualityStatus.FAIL,
                    "Code chunks must preserve a Markdown fence in render content", unfencedCodeChunks));
        }
        if (!missingTableHeaderChunks.isEmpty()) {
            issues.add(issue("MISSING_TABLE_HEADER_CONTEXT", ChunkQualityStatus.FAIL,
                    "Table slices must retain their column header in render content", missingTableHeaderChunks));
        }
        if (!anchorContaminatedChunks.isEmpty()) {
            issues.add(issue("HEADING_ANCHOR_CONTAMINATION", ChunkQualityStatus.FAIL,
                    "Heading anchors must not appear in chunk context headers", anchorContaminatedChunks));
        }
        if (!headingOnlyChildren.isEmpty()) {
            issues.add(issue("HEADING_ONLY_CHILDREN", ChunkQualityStatus.WARNING,
                    "Heading-only child chunks add retrieval noise", headingOnlyChildren));
        }
        if (!identicalSingleChildParents.isEmpty()) {
            issues.add(issue("IDENTICAL_SINGLE_CHILD_PARENTS", ChunkQualityStatus.WARNING,
                    "Single-child parents provide no additional retrieval context", identicalSingleChildParents));
        }
        if (!suspiciousStarts.isEmpty()) {
            issues.add(issue("SUSPICIOUS_CHILD_START", ChunkQualityStatus.WARNING,
                    "Some child chunks begin with punctuation or a closing token", suspiciousStarts));
        }

        double shortChildRatio = ratio(shortChildren.size(), children.size());
        double shortParentRatio = ratio(shortParents.size(), parents.size());
        double oneChildParentRatio = ratio(oneChildParents.size(), parents.size());
        if (shortChildRatio > 0.15) {
            issues.add(issue("EXCESS_SHORT_CHILDREN", ChunkQualityStatus.WARNING,
                    "Too many child chunks fall below the useful retrieval range", shortChildren));
        }
        if (shortParentRatio > 0.25) {
            issues.add(issue("EXCESS_SHORT_PARENTS", ChunkQualityStatus.WARNING,
                    "Too many parent chunks provide limited surrounding context", shortParents));
        }
        if (oneChildParentRatio > 0.25) {
            issues.add(issue("EXCESS_SINGLE_CHILD_PARENTS", ChunkQualityStatus.WARNING,
                    "Too many parents contain only one child chunk", oneChildParents));
        }

        var status = issues.stream().anyMatch(value -> value.severity() == ChunkQualityStatus.FAIL)
                ? ChunkQualityStatus.FAIL
                : issues.isEmpty() ? ChunkQualityStatus.PASS : ChunkQualityStatus.WARNING;
        int score = 100;
        score -= 30 * (int) issues.stream().filter(value -> value.severity() == ChunkQualityStatus.FAIL).count();
        score -= 7 * (int) issues.stream().filter(value -> value.severity() == ChunkQualityStatus.WARNING).count();

        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("parents", parents.size());
        metrics.put("children", children.size());
        metrics.put("mappedChunks", chunks.size() - unmapped.size());
        metrics.put("unmappedChunks", unmapped.size());
        metrics.put("headingOnlyChildren", headingOnlyChildren.size());
        metrics.put("shortChildren", shortChildren.size());
        metrics.put("shortChildRatio", rounded(shortChildRatio));
        metrics.put("shortParents", shortParents.size());
        metrics.put("shortParentRatio", rounded(shortParentRatio));
        metrics.put("oneChildParents", oneChildParents.size());
        metrics.put("oneChildParentRatio", rounded(oneChildParentRatio));
        metrics.put("identicalSingleChildParents", identicalSingleChildParents.size());
        metrics.put("emptyRenderChunks", emptyRenderChunks.size());
        metrics.put("unfencedCodeChunks", unfencedCodeChunks.size());
        metrics.put("missingTableHeaderChunks", missingTableHeaderChunks.size());
        metrics.put("anchorContaminatedChunks", anchorContaminatedChunks.size());
        metrics.put("suspiciousChildStarts", suspiciousStarts.size());
        metrics.put("childUsefulMinimumTokens", shortChildThreshold);
        metrics.put("parentUsefulMinimumTokens", shortParentThreshold);
        return new ChunkQualityReport(status, score, issues, metrics);
    }

    private static boolean isHeadingOnly(Chunk chunk, Map<UUID, DocumentBlock> blocksById) {
        return !chunk.sourceBlockIds().isEmpty() && chunk.sourceBlockIds().stream()
                .map(blocksById::get)
                .allMatch(block -> block != null && (block.type() == BlockType.TITLE || block.type() == BlockType.HEADING));
    }

    private static boolean containsType(
            Chunk chunk,
            Map<UUID, DocumentBlock> blocksById,
            BlockType type
    ) {
        return chunk.sourceBlockIds().stream().map(blocksById::get)
                .anyMatch(block -> block != null && block.type() == type);
    }

    private static boolean startsSuspiciously(String text) {
        var value = text == null ? "" : text.stripLeading();
        if (value.isEmpty()) return false;
        int character = value.codePointAt(0);
        int type = Character.getType(character);
        return "，。；：、！？,.;:!?)}]".indexOf(character) >= 0
                || type == Character.END_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION;
    }

    private static boolean missesVirtualTableHeader(
            Chunk chunk,
            ChunkingResult result,
            Map<UUID, DocumentBlock> blocksById
    ) {
        var sourceMap = result.sourceMaps().get(chunk.id());
        if (sourceMap == null) return false;
        return sourceMap.segments().stream().anyMatch(segment -> {
            var block = blocksById.get(segment.documentBlockId());
            if (block == null || block.type() != BlockType.TABLE || segment.blockLocalStart() == 0) return false;
            var lines = block.text().lines().limit(2).toList();
            return lines.size() == 2 && !chunk.renderMarkdown().contains(lines.get(0).strip() + "\n" + lines.get(1).strip());
        });
    }

    private static ChunkQualityIssue issue(
            String code,
            ChunkQualityStatus severity,
            String message,
            List<Chunk> chunks
    ) {
        return new ChunkQualityIssue(code, severity, message,
                chunks.stream().limit(20).map(value -> value.id().toString()).toList());
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double rounded(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
