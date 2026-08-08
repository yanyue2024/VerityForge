package com.yanyue.rag.domain.agent;

import java.util.UUID;

public record FactSupport(UUID evidenceId, int sourceStart, int sourceEnd) {
    public FactSupport {
        if (sourceStart < 0 || sourceEnd < sourceStart) {
            throw new IllegalArgumentException("Fact support span is invalid");
        }
    }
}
