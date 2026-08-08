package com.yanyue.rag.contract.pipeline;

import com.yanyue.rag.contract.model.ModelProfileView;
import java.util.List;

public record AiConfigView(
        PipelineConfigView publishedPipeline,
        PipelineConfigView draftPipeline,
        AssistantProfileView publishedAssistant,
        AssistantProfileView draftAssistant,
        List<ModelProfileView> modelProfiles,
        boolean previewReady
) { }
