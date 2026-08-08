package com.yanyue.rag.application.pipeline;

import com.yanyue.rag.contract.model.ModelProfileView;
import com.yanyue.rag.contract.pipeline.AiConfigDraftRequest;
import com.yanyue.rag.contract.pipeline.AiConfigPreviewView;
import com.yanyue.rag.contract.pipeline.AiConfigVersionView;
import com.yanyue.rag.contract.pipeline.AiConfigView;
import com.yanyue.rag.application.model.ModelProfileService;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AiConfigService {
    private final PipelineConfigService pipelines;
    private final AssistantProfileService assistants;
    private final ModelProfileService modelProfiles;
    private final ModelProfileRepository profileRepository;
    private final StreamingAnswerModelPort answerModel;

    public AiConfigService(
            PipelineConfigService pipelines,
            AssistantProfileService assistants,
            ModelProfileService modelProfiles,
            ModelProfileRepository profileRepository,
            StreamingAnswerModelPort answerModel
    ) {
        this.pipelines = pipelines;
        this.assistants = assistants;
        this.modelProfiles = modelProfiles;
        this.profileRepository = profileRepository;
        this.answerModel = answerModel;
    }

    public AiConfigView get(UUID organizationId) {
        var publishedPipeline = pipelines.active(organizationId);
        var draftPipeline = pipelines.draft(organizationId);
        var publishedAssistant = assistants.versions(organizationId).stream()
                .filter(value -> "PUBLISHED".equals(value.status())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No published assistant role is configured"));
        var draftAssistant = assistants.draftOrPublished(organizationId);
        var ready = !draftPipeline.id().equals(publishedPipeline.id())
                && "DRAFT".equals(draftAssistant.status())
                && draftAssistant.previewedAt() != null
                && pipelines.isDraftPreviewed(organizationId, draftPipeline.id());
        return new AiConfigView(publishedPipeline, draftPipeline, publishedAssistant, draftAssistant,
                modelProfiles.list(organizationId), ready);
    }

    public AiConfigView saveDraft(UUID organizationId, AiConfigDraftRequest request) {
        pipelines.saveDraft(organizationId, request.pipeline());
        assistants.saveDraft(organizationId, request.assistant());
        return get(organizationId);
    }

    public AiConfigView activateLanguageModel(UUID organizationId, UUID profileId) {
        pipelines.activateLanguageModel(organizationId, profileId);
        return get(organizationId);
    }

    public AiConfigPreviewView preview(UUID organizationId, String query) {
        var pipeline = pipelines.draftModel(organizationId);
        var assistantView = assistants.draftOrPublished(organizationId);
        if (!"DRAFT".equals(assistantView.status()) || pipeline.active()) {
            throw new IllegalArgumentException("Save an AI configuration draft before previewing it");
        }
        var assistant = assistants.find(organizationId, assistantView.id());
        var profile = profileRepository.findById(organizationId, pipeline.chatProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Answer model connection was not found"));
        var instruction = """
                你正在预览 VerityForge 的组织助手角色。不要声称检索到了内部资料，不要生成引用。
                当前角色：
                %s
                自然、直接地回答用户，用与用户一致的语言。
                """.formatted(assistant.roleInstruction()).strip();
        var result = answerModel.generate(pipeline.chatProfileId(), new StreamingAnswerModelPort.AnswerRequest(
                query.strip(), query.strip(), List.of(), List.of(), Math.min(60, pipeline.llmTimeoutSeconds()),
                Math.min(1024, pipeline.maxCompletionTokens()), instruction, List.of(), pipeline.temperature()),
                ignored -> { }, 1);
        pipelines.markDraftPreviewed(organizationId, pipeline.id());
        assistants.markPreviewed(organizationId, assistant.id());
        return new AiConfigPreviewView(result.content(), profile.modelName(), pipeline.temperature());
    }

    public AiConfigView publish(UUID organizationId) {
        var pipeline = pipelines.draftModel(organizationId);
        var assistant = assistants.draftOrPublished(organizationId);
        if (!"DRAFT".equals(assistant.status()) || assistant.previewedAt() == null) {
            throw new IllegalArgumentException("Test the latest assistant role draft before publishing");
        }
        pipelines.publishDraft(organizationId, pipeline.id());
        assistants.publish(organizationId, assistant.id());
        return get(organizationId);
    }

    public List<AiConfigVersionView> versions(UUID organizationId) {
        var values = new ArrayList<AiConfigVersionView>();
        var draftId = pipelines.draftModel(organizationId).id();
        var index = pipelines.versions(organizationId).size();
        for (var pipeline : pipelines.versions(organizationId)) {
            var status = pipeline.active() ? "PUBLISHED" : pipeline.id().equals(draftId) ? "DRAFT" : "ARCHIVED";
            values.add(new AiConfigVersionView(pipeline.id(), "PIPELINE", index--, status,
                    pipeline.name(), pipeline.createdAt(), pipeline.active() ? pipeline.updatedAt() : null));
        }
        assistants.versions(organizationId).forEach(value -> values.add(new AiConfigVersionView(
                value.id(), "ASSISTANT", value.version(), value.status(), value.assistantName(),
                value.createdAt(), value.publishedAt())));
        return List.copyOf(values);
    }

    public AiConfigView restore(UUID organizationId, UUID versionId) {
        if (pipelines.restoreAsDraft(organizationId, versionId)) {
            assistants.clonePublishedAsDraft(organizationId);
            return get(organizationId);
        }
        if (assistants.restoreAsDraft(organizationId, versionId)) {
            pipelines.restoreAsDraft(organizationId, pipelines.activeModel(organizationId).id());
            return get(organizationId);
        }
        throw new IllegalArgumentException("AI configuration version was not found");
    }
}
