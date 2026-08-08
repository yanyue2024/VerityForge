package com.yanyue.rag.domain.chunking.v4;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HistoricalChunkSourceMapReconstructor {
    private final ChunkSourceMapBuilder sourceMapBuilder = new ChunkSourceMapBuilder();

    public ChunkSourceMap reconstruct(UUID chunkId, String chunkText, List<HistoricalSourceBlock> orderedBlocks) {
        if (chunkText == null || chunkText.isEmpty() || orderedBlocks == null || orderedBlocks.isEmpty()) {
            return ChunkSourceMap.unmappable(chunkId, SourceMapFailureReason.SOURCE_BLOCK_MISSING);
        }
        var slices = fullBlockSlices(orderedBlocks);
        if (!slices.isEmpty()) {
            var rebuilt = sourceMapBuilder.build(chunkId, slices);
            if (rebuilt.text().equals(chunkText)) return rebuilt.sourceMap();
        }
        return reconstructUniqueWindow(chunkId, chunkText, orderedBlocks);
    }

    public ChunkSourceMap reconstructFromParent(
            UUID childChunkId,
            String childText,
            String parentText,
            ChunkSourceMap parentMap
    ) {
        if (parentMap.status() == SourceMapStatus.UNMAPPABLE) {
            return ChunkSourceMap.unmappable(childChunkId, SourceMapFailureReason.SOURCE_BLOCK_MISSING);
        }
        int start = uniqueIndex(parentText, childText);
        if (start == -2) return ChunkSourceMap.unmappable(childChunkId, SourceMapFailureReason.AMBIGUOUS_MATCH);
        if (start < 0) return ChunkSourceMap.unmappable(childChunkId, SourceMapFailureReason.TEXT_MISMATCH);
        return projectChildRange(childChunkId, start, childText.length(), parentMap);
    }

    private ChunkSourceMap reconstructUniqueWindow(
            UUID chunkId,
            String chunkText,
            List<HistoricalSourceBlock> blocks
    ) {
        HistoricalSourceBlock matchedBlock = null;
        int matchedStart = -1;
        for (var block : blocks) {
            int start = uniqueIndex(block.text(), chunkText);
            if (start == -2 || (start >= 0 && matchedBlock != null)) {
                return ChunkSourceMap.unmappable(chunkId, SourceMapFailureReason.AMBIGUOUS_MATCH);
            }
            if (start >= 0) {
                matchedBlock = block;
                matchedStart = start;
            }
        }
        if (matchedBlock == null) return ChunkSourceMap.unmappable(chunkId, SourceMapFailureReason.TEXT_MISMATCH);
        return ChunkSourceMap.mapped(chunkId, List.of(segmentForWindow(matchedBlock, matchedStart, chunkText.length())));
    }

    private ChunkSourceMap projectChildRange(UUID childId, int parentStart, int length, ChunkSourceMap parentMap) {
        int parentEnd = parentStart + length;
        var source = parentMap.segments().stream().filter(segment -> segment.contains(parentStart, parentEnd))
                .findFirst();
        if (source.isEmpty()) {
            return ChunkSourceMap.unmappable(childId, SourceMapFailureReason.CROSSES_SOURCE_SEGMENTS);
        }
        var segment = source.get();
        int blockStart = segment.blockLocalStart() + parentStart - segment.chunkLocalStart();
        Integer documentStart = projectedDocumentStart(segment, parentStart);
        var childSegment = new ChunkSourceSegment(0, 0, length, OffsetUnit.UTF16_CODE_UNIT,
                segment.documentBlockId(), blockStart, blockStart + length, OffsetUnit.UTF16_CODE_UNIT,
                documentStart, documentStart == null ? null : documentStart + length,
                segment.documentOffsetUnit(), segment.pageNumber());
        return ChunkSourceMap.mapped(childId, List.of(childSegment));
    }

    private static List<SourceBlockSlice> fullBlockSlices(List<HistoricalSourceBlock> blocks) {
        var slices = new ArrayList<SourceBlockSlice>();
        for (var block : blocks) {
            var stripped = block.text().strip();
            if (stripped.isEmpty()) continue;
            int start = block.text().indexOf(stripped);
            slices.add(new SourceBlockSlice(block.documentBlockId(), block.text(), start, start + stripped.length(),
                    block.documentSourceStart(), block.documentSourceEnd(), block.documentOffsetUnit(),
                    block.pageNumber()));
        }
        return slices;
    }

    private static ChunkSourceSegment segmentForWindow(HistoricalSourceBlock block, int start, int length) {
        Integer documentStart = hasSafeDocumentProjection(block) ? block.documentSourceStart() + start : null;
        return new ChunkSourceSegment(0, 0, length, OffsetUnit.UTF16_CODE_UNIT, block.documentBlockId(),
                start, start + length, OffsetUnit.UTF16_CODE_UNIT, documentStart,
                documentStart == null ? null : documentStart + length, block.documentOffsetUnit(), block.pageNumber());
    }

    private static Integer projectedDocumentStart(ChunkSourceSegment segment, int parentStart) {
        if (segment.documentSourceStart() == null || segment.documentOffsetUnit() != OffsetUnit.UTF16_CODE_UNIT) {
            return null;
        }
        return segment.documentSourceStart() + parentStart - segment.chunkLocalStart();
    }

    private static int uniqueIndex(String source, String target) {
        if (target == null || target.isEmpty()) return -1;
        int first = source.indexOf(target);
        if (first < 0) return -1;
        return source.indexOf(target, first + 1) >= 0 ? -2 : first;
    }

    private static boolean hasSafeDocumentProjection(HistoricalSourceBlock block) {
        return block.documentSourceStart() != null
                && block.documentSourceEnd() != null
                && block.documentOffsetUnit() == OffsetUnit.UTF16_CODE_UNIT
                && block.documentSourceEnd() - block.documentSourceStart() == block.text().length();
    }
}
