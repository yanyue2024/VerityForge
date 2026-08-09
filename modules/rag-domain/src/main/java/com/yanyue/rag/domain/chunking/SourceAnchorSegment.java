package com.yanyue.rag.domain.chunking;

import java.util.UUID;

public record SourceAnchorSegment(
        UUID documentBlockId,
        int parentLocalStart,
        int parentLocalEnd,
        int blockLocalStart,
        int blockLocalEnd,
        Integer documentSourceStart,
        Integer documentSourceEnd,
        Integer pageNumber
) {
    public SourceAnchorSegment {
        if (parentLocalStart < 0 || parentLocalEnd <= parentLocalStart) {
            throw new IllegalArgumentException("父块锚点范围无效");
        }
        if (blockLocalStart < 0 || blockLocalEnd <= blockLocalStart) {
            throw new IllegalArgumentException("文档块锚点范围无效");
        }
        if (parentLocalEnd - parentLocalStart != blockLocalEnd - blockLocalStart) {
            throw new IllegalArgumentException("锚点映射长度必须一致");
        }
    }
}
