package com.yanyue.rag.contract.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ModelProfileTestView(
        UUID profileId,
        ModelProfileTestStatus status,
        long latencyMs,
        String message,
        Map<String, Object> capabilities,
        Instant testedAt
) {
}
