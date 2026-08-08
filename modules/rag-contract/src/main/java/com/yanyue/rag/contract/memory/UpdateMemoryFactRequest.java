package com.yanyue.rag.contract.memory;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record UpdateMemoryFactRequest(
        @NotNull MemoryConfirmationStatus status,
        Instant validTo
) {
}
