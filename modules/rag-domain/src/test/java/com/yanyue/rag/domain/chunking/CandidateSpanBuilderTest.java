package com.yanyue.rag.domain.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateSpanBuilderTest {
    private final CandidateSpanBuilder builder = new CandidateSpanBuilder();

    @Test
    void preservesOriginalTextAndNeverCrossesSourceMapGap() {
        var parent = parent(List.of("部署步骤：先安装。", "限制条件：仅支持 UTF-8。"), List.of());

        var spans = builder.build(parent, "部署步骤 限制条件");

        assertEquals(2, spans.size());
        for (var span : spans) {
            assertEquals(parent.text().substring(span.localStart(), span.localEnd()), span.text());
            assertFalse(parent.sourceMap().hasDiscontinuity(span.localStart(), span.localEnd()));
        }
        assertTrue(spans.stream().noneMatch(span -> span.text().contains("。\n\n限制")));
    }

    @Test
    void producesStableSpanIdsAndRanksChildAnchorFirst() {
        var first = "普通介绍。";
        var second = "关键部署限制。";
        var base = parent(List.of(first, second), List.of());
        int secondStart = first.length() + ChunkSourceMapBuilder.SYNTHETIC_BLOCK_SEPARATOR.length();
        var anchored = new ParentContext(base.parentChunkId(), base.documentId(), base.documentVersionId(),
                base.titlePath(), base.pageRange(), base.text(),
                List.of(new ChildAnchor(UUID.randomUUID(), secondStart, base.text().length())),
                base.queryProvenance(), base.sourceMap(), base.retrievalScore());

        var firstBuild = builder.build(anchored, "部署限制");
        var secondBuild = builder.build(anchored, "部署限制");

        assertEquals(firstBuild, secondBuild);
        assertEquals(second, firstBuild.getFirst().text());
        assertEquals(0, firstBuild.getFirst().childAnchorDistance());
    }

    @Test
    void respectsTokenAndCountLimitsWithoutSplittingSurrogatePairs() {
        var parent = parent(List.of("甲😀乙。".repeat(5_000)), List.of());

        var spans = builder.build(parent, "甲乙");

        assertEquals(CandidateSpanBuilder.MAX_SPANS_PER_PARENT, spans.size());
        assertTrue(spans.stream().allMatch(span -> span.estimatedTokens() <= CandidateSpanBuilder.MAX_SPAN_TOKENS));
        assertTrue(spans.stream().allMatch(span -> !endsInsideSurrogate(parent.text(), span.localEnd())));
    }

    @Test
    void unmappableParentProducesNoCandidatesAndExposesHiddenEvidenceSignal() {
        var parentId = UUID.randomUUID();
        var context = new ParentContext(parentId, UUID.randomUUID(), UUID.randomUUID(), List.of(),
                PageRange.unknown(), "相关原文", List.of(), List.of(),
                ChunkSourceMap.unmappable(parentId, SourceMapFailureReason.AMBIGUOUS_MATCH), 1.0);

        assertTrue(context.evidenceMayBeHidden());
        assertTrue(builder.build(context, "相关").isEmpty());
    }

    private static boolean endsInsideSurrogate(String text, int end) {
        return end > 0 && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end));
    }

    private static ParentContext parent(List<String> blockTexts, List<ChildAnchor> anchors) {
        var slices = blockTexts.stream()
                .map(text -> new SourceBlockSlice(UUID.randomUUID(), text, 0, text.length(),
                        null, null, null, null))
                .toList();
        var parentId = UUID.randomUUID();
        var mapped = new ChunkSourceMapBuilder().build(parentId, slices);
        return new ParentContext(parentId, UUID.randomUUID(), UUID.randomUUID(), List.of("测试"),
                PageRange.unknown(), mapped.text(), anchors, List.of(), mapped.sourceMap(), 1.0);
    }
}
