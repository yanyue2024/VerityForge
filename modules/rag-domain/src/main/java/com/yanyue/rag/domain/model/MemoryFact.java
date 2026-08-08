package com.yanyue.rag.domain.model;

import com.yanyue.rag.contract.memory.MemoryConfirmationStatus;
import java.time.Instant;
import java.util.UUID;

public record MemoryFact(
        UUID id,
        UUID organizationId,
        UUID userId,
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
