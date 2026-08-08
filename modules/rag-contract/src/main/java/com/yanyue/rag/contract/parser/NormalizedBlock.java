package com.yanyue.rag.contract.parser;

import java.util.List;
import java.util.Map;

public record NormalizedBlock(
        String blockId,
        BlockType type,
        String text,
        int orderIndex,
        Integer pageNumber,
        List<String> headingPath,
        BoundingBox boundingBox,
        Integer sourceStart,
        Integer sourceEnd,
        String sourceOffsetUnit,
        Map<String, Object> attributes
) {
    public NormalizedBlock {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        sourceOffsetUnit = sourceOffsetUnit == null ? "UTF16_CODE_UNIT" : sourceOffsetUnit;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
