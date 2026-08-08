package com.yanyue.rag.domain.agent;

import java.util.List;
import java.util.UUID;

public record SupportedSurface(String statement, List<UUID> evidenceIds) {
    public SupportedSurface {
        statement = statement == null ? "" : statement.strip().replaceAll("\\s+", " ");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
