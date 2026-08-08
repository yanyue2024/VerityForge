package com.yanyue.rag.domain.chunking.v4;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public record SourceAnchor(
        UUID documentVersionId,
        UUID parentChunkId,
        int parentLocalStart,
        int parentLocalEnd,
        OffsetUnit parentOffsetUnit,
        OffsetUnit blockOffsetUnit,
        OffsetUnit documentOffsetUnit,
        List<SourceAnchorSegment> segments
) {
    public SourceAnchor {
        segments = segments == null ? List.of() : List.copyOf(segments);
        if (parentLocalStart < 0 || parentLocalEnd <= parentLocalStart) {
            throw new IllegalArgumentException("父块 SourceAnchor 范围无效");
        }
        if (parentOffsetUnit != OffsetUnit.UTF16_CODE_UNIT || blockOffsetUnit != OffsetUnit.UTF16_CODE_UNIT) {
            throw new IllegalArgumentException("v4 SourceAnchor 必须使用 UTF-16 code unit");
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("SourceAnchor 至少需要一个真实 Block 区段");
        }
        int previousEnd = parentLocalStart;
        for (var segment : segments) {
            if (segment.parentLocalStart() < parentLocalStart
                    || segment.parentLocalEnd() > parentLocalEnd
                    || segment.parentLocalStart() < previousEnd) {
                throw new IllegalArgumentException("SourceAnchor 区段必须位于父块范围内且按顺序排列");
            }
            previousEnd = segment.parentLocalEnd();
        }
    }

    public String restoreFromBlocks(Function<UUID, String> blockTextLoader) {
        var restored = new StringBuilder();
        for (var segment : segments) {
            var blockText = blockTextLoader.apply(segment.documentBlockId());
            if (blockText == null || segment.blockLocalEnd() > blockText.length()) {
                throw new IllegalStateException("SourceAnchor 无法从当前文档块还原");
            }
            restored.append(blockText, segment.blockLocalStart(), segment.blockLocalEnd());
        }
        return restored.toString();
    }
}
