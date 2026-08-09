package com.yanyue.rag.domain.chunking;

import java.util.UUID;

public record ChildAnchor(UUID childChunkId, int parentLocalStart, int parentLocalEnd) {
    public ChildAnchor {
        if (parentLocalStart < 0 || parentLocalEnd <= parentLocalStart) {
            throw new IllegalArgumentException("子块锚点范围无效");
        }
    }

    public int distanceTo(int start, int end) {
        if (parentLocalStart < end && start < parentLocalEnd) return 0;
        return end <= parentLocalStart ? parentLocalStart - end : start - parentLocalEnd;
    }
}
