package com.yanyue.rag.domain.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkSourceMapTest {
    @Test
    void mapsUtf16RangesAndLeavesSyntheticSeparatorUnmapped() {
        var chunkId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var firstBlock = UUID.randomUUID();
        var secondBlock = UUID.randomUUID();
        var mapped = new ChunkSourceMapBuilder().build(chunkId, List.of(
                new SourceBlockSlice(firstBlock, "前缀甲😀乙后缀", 2, 6, 100, 108,
                        OffsetUnit.UTF16_CODE_UNIT, 1),
                new SourceBlockSlice(secondBlock, "第二段", 0, 3, 200, 203,
                        OffsetUnit.UTF16_CODE_UNIT, 2)
        ));

        assertEquals("甲😀乙\n\n第二段", mapped.text());
        assertTrue(mapped.sourceMap().hasDiscontinuity(3, 7));
        assertTrue(mapped.sourceMap().anchorFor(versionId, 4, 7).isEmpty());

        var anchor = mapped.sourceMap().anchorFor(versionId, 0, 4).orElseThrow();
        assertEquals("甲😀乙", anchor.restoreFromBlocks(Map.of(
                firstBlock, "前缀甲😀乙后缀",
                secondBlock, "第二段"
        )::get));
        assertEquals(102, anchor.segments().getFirst().documentSourceStart());
        assertEquals(106, anchor.segments().getFirst().documentSourceEnd());
    }

    @Test
    void rejectsRangesCrossingTwoRealBlocksEvenWhenTheyAreAdjacentInParentText() {
        var mapped = new ChunkSourceMapBuilder().build(UUID.randomUUID(), List.of(
                slice("表头|说明", 1),
                slice("数据|内容", 2)
        ));

        assertFalse(mapped.sourceMap().anchorFor(UUID.randomUUID(), 0, mapped.text().length()).isPresent());
    }

    @Test
    void anchorsAWholeParentAcrossRealBlocksWithoutPretendingTheGapIsOneBlock() {
        var mapped = new ChunkSourceMapBuilder().build(UUID.randomUUID(), List.of(
                slice("第一段证据", 1),
                slice("第二段证据", 2)
        ));

        var anchor = mapped.sourceMap().anchorForParent(
                UUID.randomUUID(), mapped.text().length()).orElseThrow();

        assertEquals(0, anchor.parentLocalStart());
        assertEquals(mapped.text().length(), anchor.parentLocalEnd());
        assertEquals(2, anchor.segments().size());
        assertTrue(anchor.segments().getFirst().parentLocalEnd()
                < anchor.segments().getLast().parentLocalStart());
    }

    private static SourceBlockSlice slice(String text, int page) {
        return new SourceBlockSlice(UUID.randomUUID(), text, 0, text.length(), null, null, null, page);
    }
}
