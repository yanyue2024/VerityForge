package com.yanyue.rag.domain.chunking;

import java.util.UUID;

public record HistoricalSourceBlock(
        UUID documentBlockId,
        String text,
        Integer documentSourceStart,
        Integer documentSourceEnd,
        OffsetUnit documentOffsetUnit,
        Integer pageNumber
) {
    public HistoricalSourceBlock {
        text = text == null ? "" : text;
    }
}
