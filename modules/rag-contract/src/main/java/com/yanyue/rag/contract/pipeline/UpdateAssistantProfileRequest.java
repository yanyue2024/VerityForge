package com.yanyue.rag.contract.pipeline;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateAssistantProfileRequest(
        @NotBlank @Size(max = 80) String assistantName,
        @NotBlank @Size(max = 1000) String identity,
        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 200) String> capabilities,
        @NotBlank @Size(max = 500) String tone,
        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 300) String> boundaries,
        @Size(max = 4000) String additionalInstructions
) { }
