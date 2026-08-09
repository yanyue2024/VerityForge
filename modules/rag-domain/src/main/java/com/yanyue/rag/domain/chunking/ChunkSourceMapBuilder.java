package com.yanyue.rag.domain.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ChunkSourceMapBuilder {
    public static final String SYNTHETIC_BLOCK_SEPARATOR = "\n\n";

    public MappedChunkText build(UUID chunkId, List<SourceBlockSlice> slices) {
        if (slices == null || slices.isEmpty()) throw new IllegalArgumentException("源 Block 切片不能为空");
        var text = new StringBuilder();
        var segments = new ArrayList<ChunkSourceSegment>();
        for (int index = 0; index < slices.size(); index++) {
            if (index > 0) text.append(SYNTHETIC_BLOCK_SEPARATOR);
            appendSlice(text, segments, index, slices.get(index));
        }
        return new MappedChunkText(text.toString(), ChunkSourceMap.mapped(chunkId, segments));
    }

    private static void appendSlice(
            StringBuilder text,
            List<ChunkSourceSegment> segments,
            int order,
            SourceBlockSlice slice
    ) {
        int chunkStart = text.length();
        var sliceText = slice.text();
        text.append(sliceText);
        segments.add(new ChunkSourceSegment(
                order,
                chunkStart,
                text.length(),
                OffsetUnit.UTF16_CODE_UNIT,
                slice.documentBlockId(),
                slice.blockLocalStart(),
                slice.blockLocalEnd(),
                OffsetUnit.UTF16_CODE_UNIT,
                projectedStart(slice),
                projectedEnd(slice),
                slice.documentOffsetUnit(),
                slice.pageNumber()
        ));
    }

    private static Integer projectedStart(SourceBlockSlice slice) {
        if (!hasSafeDocumentProjection(slice)) {
            return null;
        }
        return slice.documentSourceStart() + slice.blockLocalStart();
    }

    private static Integer projectedEnd(SourceBlockSlice slice) {
        var start = projectedStart(slice);
        return start == null ? null : start + slice.blockLocalEnd() - slice.blockLocalStart();
    }

    private static boolean hasSafeDocumentProjection(SourceBlockSlice slice) {
        return slice.documentSourceStart() != null
                && slice.documentOffsetUnit() == OffsetUnit.UTF16_CODE_UNIT
                && slice.documentSourceEnd() - slice.documentSourceStart() == slice.blockText().length();
    }
}
