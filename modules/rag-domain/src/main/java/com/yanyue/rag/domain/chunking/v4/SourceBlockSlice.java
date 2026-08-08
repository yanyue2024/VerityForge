package com.yanyue.rag.domain.chunking.v4;

import java.util.UUID;

public record SourceBlockSlice(
        UUID documentBlockId,
        String blockText,
        int blockLocalStart,
        int blockLocalEnd,
        Integer documentSourceStart,
        Integer documentSourceEnd,
        OffsetUnit documentOffsetUnit,
        Integer pageNumber
) {
    public SourceBlockSlice {
        if (blockText == null || blockLocalStart < 0 || blockLocalEnd <= blockLocalStart
                || blockLocalEnd > blockText.length()) {
            throw new IllegalArgumentException("源 Block 切片范围无效");
        }
        if ((documentSourceStart == null) != (documentSourceEnd == null)) {
            throw new IllegalArgumentException("文档源范围必须同时存在或同时为空");
        }
        if (documentSourceStart != null && documentOffsetUnit == null) {
            throw new IllegalArgumentException("文档源范围存在时必须声明 Offset Unit");
        }
    }

    public String text() {
        return blockText.substring(blockLocalStart, blockLocalEnd);
    }
}
