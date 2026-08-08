package com.yanyue.rag.contract.knowledge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CreateUploadIntentRequest(
        @NotBlank String title,
        @NotBlank String fileName,
        @NotBlank String contentType,
        @Min(1) @Max(536_870_912) long byteSize,
        @Pattern(regexp = "[a-fA-F0-9]{64}") String sha256,
        Map<String, Object> metadata,
        Instant validFrom,
        Instant validTo,
        UUID documentId
) {
}
