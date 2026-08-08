package com.yanyue.rag.contract.memory;

import java.time.Instant;
import java.util.UUID;

public record MemoryFactView(
        UUID id,
        String factText,
        UUID sourceMessageId,
        double confidence,
        MemoryConfirmationStatus status,
        Instant validFrom,
        Instant validTo,
        Instant createdAt,
        Instant updatedAt
) {
}
