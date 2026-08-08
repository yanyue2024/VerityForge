package com.yanyue.rag.application.pipeline;

import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProfileTestStatus;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.contract.pipeline.PipelineConfigView;
import com.yanyue.rag.contract.pipeline.UpdatePipelineConfigRequest;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.model.PipelineConfig;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import com.yanyue.rag.domain.port.PipelineConfigRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PipelineConfigService {
    public static final String PIPELINE_VERSION = "fast-rag-v2";
    public static final String PROMPT_VERSION = "rewrite-v1+answer-v2";

    private final PipelineConfigRepository repository;
    private final ModelProfileRepository profiles;
    private final Clock clock;

    public PipelineConfigService(PipelineConfigRepository repository, ModelProfileRepository profiles, Clock clock) {
        this.repository = repository;
        this.profiles = profiles;
        this.clock = clock;
    }

    public PipelineConfigView active(UUID organizationId) {
        return repository.findActive(organizationId).map(this::toView)
                .orElseThrow(() -> new IllegalStateException("No active Pipeline Config is configured"));
    }

    public PipelineConfig activeModel(UUID organizationId) {
        return repository.findActive(organizationId)
                .orElseThrow(() -> new IllegalStateException("No active Pipeline Config is configured"));
    }

    public PipelineConfig draftModel(UUID organizationId) {
        return repository.findDraft(organizationId).orElseGet(() -> activeModel(organizationId));
    }

    public PipelineConfigView draft(UUID organizationId) {
        return toView(draftModel(organizationId));
    }

    public PipelineConfig resolve(UUID organizationId, UUID chatOverride) {
        return resolve(organizationId, chatOverride, null);
    }

    public PipelineConfig resolve(UUID organizationId, UUID chatOverride, UUID preferredConfigId) {
        var config = preferredConfigId == null
                ? repository.findActive(organizationId)
                    .orElseThrow(() -> new IllegalStateException("No active Pipeline Config is configured"))
                : repository.findById(organizationId, preferredConfigId)
                    .orElseThrow(() -> new IllegalStateException("The Run Pipeline Config is no longer available"));
        if (chatOverride != null) requireProfileAny(organizationId, chatOverride,
                ModelProfileType.CHAT, ModelProfileType.QUERY_REWRITE);
        return config;
    }

    public PipelineConfigView activate(UUID organizationId, UpdatePipelineConfigRequest request) {
        validateBindings(organizationId, request, false);
        if (request.rerankCandidateLimit() > request.rrfCandidateLimit()) {
            throw new IllegalArgumentException("Rerank candidate limit cannot exceed the RRF candidate limit");
        }
        return toView(repository.activate(config(organizationId, request)));
    }

    public PipelineConfigView activateLanguageModel(UUID organizationId, UUID profileId) {
        requirePublishableAny(organizationId, profileId,
                ModelProfileType.CHAT, ModelProfileType.QUERY_REWRITE);
        var source = activeModel(organizationId);
        var now = clock.instant();
        var activated = new PipelineConfig(
                UUID.randomUUID(), source.organizationId(), source.name(), source.pipelineVersion(),
                source.promptVersion(), profileId, profileId, source.rerankProfileId(),
                source.keywordTopK(), source.semanticTopK(), source.rrfCandidateLimit(),
                source.rerankCandidateLimit(), source.finalContextGroups(), source.contextTokenBudget(),
                source.minimumRerankScore(), source.fastTimeoutSeconds(), source.maxIterations(),
                source.maxRetrievalRounds(), source.maxSubQueries(), source.maxSearchCalls(),
                source.maxDeepReadCalls(), source.maxToolCallsPerRound(), source.maxFinalReferences(),
                source.recentTurns(), source.maxContextTokens(), source.llmTimeoutSeconds(),
                source.agenticLoopTimeoutSeconds(), source.toolTimeoutSeconds(), source.maxCompletionTokens(),
                source.temperature(), source.parallelToolCalls(), source.requireDeepReadBeforeAnswer(),
                false, now, now
        );
        return toView(repository.activate(activated));
    }

    public PipelineConfigView saveDraft(UUID organizationId, UpdatePipelineConfigRequest request) {
        validateBindings(organizationId, request, false);
        return toView(repository.saveDraft(config(organizationId, request)));
    }

    public void markDraftPreviewed(UUID organizationId, UUID configId) {
        repository.markDraftPreviewed(organizationId, configId);
    }

    public boolean isDraftPreviewed(UUID organizationId, UUID configId) {
        return repository.isDraftPreviewed(organizationId, configId);
    }

    public PipelineConfigView publishDraft(UUID organizationId, UUID configId) {
        var draft = repository.findDraft(organizationId)
                .filter(value -> value.id().equals(configId))
                .orElseThrow(() -> new IllegalArgumentException("Pipeline draft was not found"));
        if (!repository.isDraftPreviewed(organizationId, configId)) {
            throw new IllegalArgumentException("Test the latest AI configuration draft before publishing");
        }
        requireUnifiedLanguageProfile(draft.chatProfileId(), draft.queryRewriteProfileId());
        requirePublishableAny(organizationId, draft.chatProfileId(),
                ModelProfileType.CHAT, ModelProfileType.QUERY_REWRITE);
        requireProfile(organizationId, draft.rerankProfileId(), ModelProfileType.RERANK);
        return toView(repository.publishDraft(organizationId, configId));
    }

    public List<PipelineConfigView> versions(UUID organizationId) {
        return repository.findVersions(organizationId).stream().map(this::toView).toList();
    }

    public boolean restoreAsDraft(UUID organizationId, UUID configId) {
        var source = repository.findById(organizationId, configId).orElse(null);
        if (source == null) return false;
        var now = clock.instant();
        repository.saveDraft(new PipelineConfig(UUID.randomUUID(), source.organizationId(),
                source.name() + " · 恢复草稿", source.pipelineVersion(), source.promptVersion(),
                source.chatProfileId(), source.queryRewriteProfileId(), source.rerankProfileId(),
                source.keywordTopK(), source.semanticTopK(), source.rrfCandidateLimit(), source.rerankCandidateLimit(),
                source.finalContextGroups(), source.contextTokenBudget(), source.minimumRerankScore(),
                source.fastTimeoutSeconds(), source.maxIterations(), source.maxRetrievalRounds(), source.maxSubQueries(),
                source.maxSearchCalls(), source.maxDeepReadCalls(), source.maxToolCallsPerRound(),
                source.maxFinalReferences(), source.recentTurns(), source.maxContextTokens(), source.llmTimeoutSeconds(),
                source.agenticLoopTimeoutSeconds(), source.toolTimeoutSeconds(), source.maxCompletionTokens(),
                source.temperature(), source.parallelToolCalls(), source.requireDeepReadBeforeAnswer(),
                false, now, now));
        return true;
    }

    private PipelineConfig config(UUID organizationId, UpdatePipelineConfigRequest request) {
        var now = clock.instant();
        return new PipelineConfig(
                UUID.randomUUID(), organizationId, request.name().strip(), PIPELINE_VERSION, PROMPT_VERSION,
                request.chatProfileId(), request.queryRewriteProfileId(), request.rerankProfileId(),
                request.keywordTopK(), request.semanticTopK(), request.rrfCandidateLimit(),
                request.rerankCandidateLimit(), request.finalContextGroups(), request.contextTokenBudget(),
                request.minimumRerankScore(), request.fastTimeoutSeconds(),
                value(request.maxIterations(), 35), value(request.maxRetrievalRounds(), 5),
                value(request.maxSubQueries(), 8), value(request.maxSearchCalls(), 16),
                value(request.maxDeepReadCalls(), 20), value(request.maxToolCallsPerRound(), 6),
                value(request.maxFinalReferences(), 16), value(request.recentTurns(), 5),
                value(request.maxContextTokens(), 200_000), value(request.llmTimeoutSeconds(), 120),
                value(request.agenticLoopTimeoutSeconds(), 300),
                value(request.toolTimeoutSeconds(), 60), value(request.maxCompletionTokens(), 2_048),
                request.temperature() == null ? 0.7 : request.temperature(),
                Boolean.TRUE.equals(request.parallelToolCalls()),
                request.requireDeepReadBeforeAnswer() == null || request.requireDeepReadBeforeAnswer(),
                false, now, now
        );
    }

    private void validateBindings(UUID organizationId, UpdatePipelineConfigRequest request, boolean publish) {
        requireUnifiedLanguageProfile(request.chatProfileId(), request.queryRewriteProfileId());
        requireProfileAny(organizationId, request.chatProfileId(),
                ModelProfileType.CHAT, ModelProfileType.QUERY_REWRITE);
        requireProfile(organizationId, request.rerankProfileId(), ModelProfileType.RERANK);
    }

    private void requireUnifiedLanguageProfile(UUID chatProfileId, UUID queryRewriteProfileId) {
        if (!java.util.Objects.equals(chatProfileId, queryRewriteProfileId)) {
            throw new IllegalArgumentException("问题理解、改写、规划和最终回答必须使用同一个语言模型");
        }
    }

    private ModelProfile requireProfile(UUID organizationId, UUID profileId, ModelProfileType type) {
        var profile = profiles.findById(organizationId, profileId)
                .orElseThrow(() -> new IllegalArgumentException(type + " model Profile was not found"));
        if (!profile.enabled() || profile.profileType() != type) {
            throw new IllegalArgumentException(type + " model Profile is disabled or has the wrong type");
        }
        return profile;
    }

    private ModelProfile requireProfileAny(UUID organizationId, UUID profileId, ModelProfileType... types) {
        var profile = profiles.findById(organizationId, profileId)
                .orElseThrow(() -> new IllegalArgumentException("Model Profile was not found"));
        var matches = java.util.Arrays.stream(types).anyMatch(type -> type == profile.profileType());
        if (!profile.enabled() || !matches) {
            throw new IllegalArgumentException("Model Profile is disabled or has the wrong type");
        }
        return profile;
    }

    private ModelProfile requirePublishableAny(UUID organizationId, UUID profileId, ModelProfileType... types) {
        var profile = requireProfileAny(organizationId, profileId, types);
        validatePublishableModel(profile);
        return profile;
    }

    private void validatePublishableModel(ModelProfile profile) {
        if (profile.provider() != ModelProvider.OPENAI_COMPATIBLE) {
            throw new IllegalArgumentException("The first AI configuration version only supports OpenAI-compatible language models");
        }
        if (profile.testStatus() != ModelProfileTestStatus.PASSED) {
            throw new IllegalArgumentException("Test every selected language model connection before publishing");
        }
    }

    private PipelineConfigView toView(PipelineConfig config) {
        return new PipelineConfigView(
                config.id(), config.name(), config.pipelineVersion(), config.promptVersion(), config.chatProfileId(),
                config.queryRewriteProfileId(), config.rerankProfileId(), config.keywordTopK(), config.semanticTopK(),
                config.rrfCandidateLimit(), config.rerankCandidateLimit(), config.finalContextGroups(),
                config.contextTokenBudget(), config.minimumRerankScore(), config.fastTimeoutSeconds(),
                config.maxIterations(), config.maxRetrievalRounds(), config.maxSubQueries(), config.maxSearchCalls(),
                config.maxDeepReadCalls(), config.maxToolCallsPerRound(), config.maxFinalReferences(), config.recentTurns(),
                config.maxContextTokens(), config.llmTimeoutSeconds(), config.agenticLoopTimeoutSeconds(),
                config.toolTimeoutSeconds(), config.maxCompletionTokens(),
                config.temperature(), config.parallelToolCalls(), config.requireDeepReadBeforeAnswer(),
                config.active(), config.createdAt(), config.updatedAt()
        );
    }

    private int value(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
