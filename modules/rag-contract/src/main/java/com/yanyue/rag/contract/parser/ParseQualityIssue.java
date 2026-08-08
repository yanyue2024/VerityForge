package com.yanyue.rag.contract.parser;

import java.util.List;

public record ParseQualityIssue(
        String code,
        ParseQualityStatus severity,
        String message,
        List<String> blockIds
) {
    public ParseQualityIssue {
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
    }
}
