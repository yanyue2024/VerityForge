package com.yanyue.rag.domain.chunking.v4;

import java.util.UUID;

public record ChunkSourceSegment(
        int segmentOrder,
        int chunkLocalStart,
        int chunkLocalEnd,
        OffsetUnit chunkOffsetUnit,
        UUID documentBlockId,
        int blockLocalStart,
        int blockLocalEnd,
        OffsetUnit blockOffsetUnit,
        Integer documentSourceStart,
        Integer documentSourceEnd,
        OffsetUnit documentOffsetUnit,
        Integer pageNumber
) {
    public ChunkSourceSegment {
        if (segmentOrder < 0) throw new IllegalArgumentException("segmentOrder 不能为负数");
        if (chunkLocalStart < 0 || chunkLocalEnd <= chunkLocalStart) {
            throw new IllegalArgumentException("Chunk 源区段范围无效");
        }
        if (blockLocalStart < 0 || blockLocalEnd <= blockLocalStart) {
            throw new IllegalArgumentException("Block 源区段范围无效");
        }
        if (chunkLocalEnd - chunkLocalStart != blockLocalEnd - blockLocalStart) {
            throw new IllegalArgumentException("Chunk 与 Block 区段长度必须一致");
        }
        if (chunkOffsetUnit != OffsetUnit.UTF16_CODE_UNIT || blockOffsetUnit != OffsetUnit.UTF16_CODE_UNIT) {
            throw new IllegalArgumentException("v4 仅支持 UTF-16 code unit 本地下标");
        }
        if ((documentSourceStart == null) != (documentSourceEnd == null)) {
            throw new IllegalArgumentException("文档源范围必须同时存在或同时为空");
        }
        if (documentSourceStart != null && documentSourceEnd <= documentSourceStart) {
            throw new IllegalArgumentException("文档源范围无效");
        }
        if (documentSourceStart != null && documentOffsetUnit == null) {
            throw new IllegalArgumentException("文档源范围存在时必须声明 Offset Unit");
        }
    }

    public boolean contains(int start, int end) {
        return chunkLocalStart <= start && end <= chunkLocalEnd;
    }
}
