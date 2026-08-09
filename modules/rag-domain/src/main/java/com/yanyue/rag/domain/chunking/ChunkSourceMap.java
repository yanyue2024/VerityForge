package com.yanyue.rag.domain.chunking;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record ChunkSourceMap(
        UUID chunkId,
        SourceMapStatus status,
        SourceMapFailureReason failureReason,
        List<ChunkSourceSegment> segments
) {
    public ChunkSourceMap {
        segments = segments == null ? List.of() : segments.stream()
                .sorted(Comparator.comparingInt(ChunkSourceSegment::segmentOrder))
                .toList();
        if (status == SourceMapStatus.MAPPED && segments.isEmpty()) {
            throw new IllegalArgumentException("MAPPED Source Map 至少需要一个区段");
        }
        if (status == SourceMapStatus.MAPPED && failureReason != SourceMapFailureReason.NONE) {
            throw new IllegalArgumentException("MAPPED Source Map 不能携带失败原因");
        }
        if (status == SourceMapStatus.UNMAPPABLE && failureReason == SourceMapFailureReason.NONE) {
            throw new IllegalArgumentException("UNMAPPABLE Source Map 必须携带失败原因");
        }
        validateOrder(segments);
    }

    public static ChunkSourceMap mapped(UUID chunkId, List<ChunkSourceSegment> segments) {
        return new ChunkSourceMap(chunkId, SourceMapStatus.MAPPED, SourceMapFailureReason.NONE, segments);
    }

    public static ChunkSourceMap unmappable(UUID chunkId, SourceMapFailureReason reason) {
        return new ChunkSourceMap(chunkId, SourceMapStatus.UNMAPPABLE, reason, List.of());
    }

    public Optional<SourceAnchor> anchorFor(UUID documentVersionId, int start, int end) {
        if (status != SourceMapStatus.MAPPED || start < 0 || end <= start) return Optional.empty();
        return segments.stream().filter(segment -> segment.contains(start, end)).findFirst()
                .map(segment -> toAnchor(documentVersionId, segment, start, end));
    }

    /**
     * Build an anchor for a complete parent context. Unlike CandidateSpan
     * anchors, this may contain several source blocks and synthetic gaps. The
     * parent range remains exact while each real source segment stays
     * independently verifiable.
     */
    public Optional<SourceAnchor> anchorForParent(UUID documentVersionId, int parentLength) {
        if (status != SourceMapStatus.MAPPED || parentLength < 1 || segments.isEmpty()) return Optional.empty();
        var anchors = segments.stream()
                .filter(segment -> segment.chunkLocalStart() < parentLength)
                .map(segment -> {
                    int end = Math.min(segment.chunkLocalEnd(), parentLength);
                    int length = end - segment.chunkLocalStart();
                    Integer documentStart = projectDocumentStart(segment, segment.chunkLocalStart());
                    Integer documentEnd = documentStart == null ? null : documentStart + length;
                    return new SourceAnchorSegment(segment.documentBlockId(), segment.chunkLocalStart(), end,
                            segment.blockLocalStart(), segment.blockLocalStart() + length,
                            documentStart, documentEnd, segment.pageNumber());
                })
                .filter(segment -> segment.parentLocalEnd() > segment.parentLocalStart())
                .toList();
        if (anchors.isEmpty()) return Optional.empty();
        return Optional.of(new SourceAnchor(documentVersionId, chunkId, 0, parentLength,
                OffsetUnit.UTF16_CODE_UNIT, OffsetUnit.UTF16_CODE_UNIT,
                segments.getFirst().documentOffsetUnit(), anchors));
    }

    public boolean hasDiscontinuity(int start, int end) {
        if (status != SourceMapStatus.MAPPED || start < 0 || end <= start) return true;
        return segments.stream().noneMatch(segment -> segment.contains(start, end));
    }

    private SourceAnchor toAnchor(UUID versionId, ChunkSourceSegment segment, int start, int end) {
        int blockStart = segment.blockLocalStart() + start - segment.chunkLocalStart();
        int blockEnd = blockStart + end - start;
        Integer documentStart = projectDocumentStart(segment, start);
        Integer documentEnd = documentStart == null ? null : documentStart + end - start;
        var anchorSegment = new SourceAnchorSegment(segment.documentBlockId(), start, end, blockStart, blockEnd,
                documentStart, documentEnd, segment.pageNumber());
        return new SourceAnchor(versionId, chunkId, start, end, OffsetUnit.UTF16_CODE_UNIT,
                OffsetUnit.UTF16_CODE_UNIT, segment.documentOffsetUnit(), List.of(anchorSegment));
    }

    private static Integer projectDocumentStart(ChunkSourceSegment segment, int start) {
        if (segment.documentSourceStart() == null || segment.documentOffsetUnit() != OffsetUnit.UTF16_CODE_UNIT) {
            return null;
        }
        return segment.documentSourceStart() + start - segment.chunkLocalStart();
    }

    private static void validateOrder(List<ChunkSourceSegment> segments) {
        int previousOrder = -1;
        int previousEnd = -1;
        for (var segment : segments) {
            if (segment.segmentOrder() != previousOrder + 1 || segment.chunkLocalStart() < previousEnd) {
                throw new IllegalArgumentException("Source Map 区段必须按顺序排列且不得重叠");
            }
            previousOrder = segment.segmentOrder();
            previousEnd = segment.chunkLocalEnd();
        }
    }
}
