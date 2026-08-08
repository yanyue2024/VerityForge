package com.yanyue.rag.contract.chat;

import java.time.Instant;
import java.util.UUID;

public record StreamEvent(
        UUID eventId,
        UUID runId,
        long sequence,
        StreamEventType type,
        Instant timestamp,
        Object payload
) {
}
