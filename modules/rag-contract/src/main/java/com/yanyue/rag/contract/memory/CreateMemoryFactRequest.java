package com.yanyue.rag.contract.memory;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record CreateMemoryFactRequest(
        @NotBlank @Size(max = 2000) String factText,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        UUID sourceMessageId,
        Instant validFrom,
        Instant validTo
) {
}
