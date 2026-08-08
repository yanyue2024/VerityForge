package com.yanyue.rag.domain.knowledge;

import com.yanyue.rag.contract.parser.BlockType;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DocumentBlock(
        UUID id,
        UUID documentVersionId,
        BlockType type,
        int orderIndex,
        String text,
        Integer pageNumber,
        List<String> headingPath,
        Integer sourceStart,
        Integer sourceEnd,
        OffsetUnit sourceOffsetUnit,
        String blockHash,
        Map<String, Object> attributes
) {
    public DocumentBlock {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
