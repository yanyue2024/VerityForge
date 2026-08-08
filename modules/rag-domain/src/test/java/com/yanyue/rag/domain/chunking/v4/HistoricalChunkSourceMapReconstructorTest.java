package com.yanyue.rag.domain.chunking.v4;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoricalChunkSourceMapReconstructorTest {
    private final HistoricalChunkSourceMapReconstructor reconstructor =
            new HistoricalChunkSourceMapReconstructor();

    @Test
    void reconstructsLegacyParentUsingOrderedStrippedBlocks() {
        var first = block("  第一段  ");
        var second = block("第二段");

        var sourceMap = reconstructor.reconstruct(UUID.randomUUID(), "第一段\n\n第二段", List.of(first, second));

        assertEquals(SourceMapStatus.MAPPED, sourceMap.status());
        assertEquals(2, sourceMap.segments().size());
        assertEquals(2, sourceMap.segments().getFirst().blockLocalStart());
    }

    @Test
    void reconstructsOrderedParentWhenDifferentBlocksContainTheSameText() {
        var first = block("重复行");
        var second = block("重复行");

        var sourceMap = reconstructor.reconstruct(UUID.randomUUID(), "重复行\n\n重复行", List.of(first, second));

        assertEquals(SourceMapStatus.MAPPED, sourceMap.status());
        assertEquals(2, sourceMap.segments().size());
        assertEquals(first.documentBlockId(), sourceMap.segments().getFirst().documentBlockId());
        assertEquals(second.documentBlockId(), sourceMap.segments().getLast().documentBlockId());
    }

    @Test
    void reconstructsOnlyAUniqueSingleBlockWindow() {
        var sourceMap = reconstructor.reconstruct(UUID.randomUUID(), "唯一窗口", List.of(
                block("前文唯一窗口后文"),
                block("无关内容")
        ));

        assertEquals(SourceMapStatus.MAPPED, sourceMap.status());
        assertEquals(2, sourceMap.segments().getFirst().blockLocalStart());
    }

    @Test
    void marksRepeatedWindowAsUnmappable() {
        var sourceMap = reconstructor.reconstruct(UUID.randomUUID(), "重复", List.of(block("重复内容，重复出现")));

        assertEquals(SourceMapStatus.UNMAPPABLE, sourceMap.status());
        assertEquals(SourceMapFailureReason.AMBIGUOUS_MATCH, sourceMap.failureReason());
    }

    @Test
    void childCrossingSyntheticParentSeparatorIsUnmappable() {
        var parentId = UUID.randomUUID();
        var mapped = new ChunkSourceMapBuilder().build(parentId, List.of(
                slice("第一段"),
                slice("第二段")
        ));

        var sourceMap = reconstructor.reconstructFromParent(UUID.randomUUID(), "段\n\n第", mapped.text(),
                mapped.sourceMap());

        assertEquals(SourceMapStatus.UNMAPPABLE, sourceMap.status());
        assertEquals(SourceMapFailureReason.CROSSES_SOURCE_SEGMENTS, sourceMap.failureReason());
    }

    private static HistoricalSourceBlock block(String text) {
        return new HistoricalSourceBlock(UUID.randomUUID(), text, null, null, null, null);
    }

    private static SourceBlockSlice slice(String text) {
        return new SourceBlockSlice(UUID.randomUUID(), text, 0, text.length(), null, null, null, null);
    }
}
