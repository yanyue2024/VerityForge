package com.yanyue.rag.contract.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record AiConfigDraftRequest(
        @NotNull @Valid UpdatePipelineConfigRequest pipeline,
        @NotNull @Valid UpdateAssistantProfileRequest assistant
) { }
