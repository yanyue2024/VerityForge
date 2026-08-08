package com.yanyue.rag.domain.port;

import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.model.PipelineConfig;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface RunRecordPort {
    void create(UUID runId, UUID organizationId, UUID userId, UUID conversationId, CreateRunRequest request);

    default ReprocessSeed prepareReprocess(
            UUID sourceRunId,
            UUID newRunId,
            UUID organizationId,
            UUID userId
    ) {
        throw new UnsupportedOperationException("Reprocessing is not supported");
    }

    default Optional<UUID> pipelineConfigId(UUID runId) {
        return Optional.empty();
    }

    default void markRouting(UUID runId) {
    }

    void markRunning(UUID runId, RunMode selectedMode);
    void applyRuntime(UUID runId, PipelineConfig config, UUID chatProfileId);

    default void applyAgentHybridRuntime(UUID runId, PipelineConfig config, UUID chatProfileId) {
        applyRuntime(runId, config, chatProfileId);
    }

    default void applyAgentV4Runtime(UUID runId, PipelineConfig config, UUID chatProfileId) {
        applyRuntime(runId, config, chatProfileId);
    }

    default void applyAgentV5Runtime(
            UUID runId,
            PipelineConfig config,
            UUID chatProfileId,
            Map<String, Object> effectiveLimits
    ) {
        applyRuntime(runId, config, chatProfileId);
    }

    default void applyAgentV7Runtime(
            UUID runId,
            PipelineConfig config,
            UUID chatProfileId,
            Map<String, Object> effectiveLimits
    ) {
        applyAgentV5Runtime(runId, config, chatProfileId, effectiveLimits);
    }

    default void applyAgentV8Runtime(
            UUID runId,
            PipelineConfig config,
            UUID chatProfileId,
            Map<String, Object> effectiveLimits,
            String promptVersion
    ) {
        applyAgentV7Runtime(runId, config, chatProfileId, effectiveLimits);
    }

    default void applyAgentRuntime(UUID runId, PipelineConfig config, UUID chatProfileId) {
        applyRuntime(runId, config, chatProfileId);
    }
    void markNoAnswer(UUID runId, String reason);
    default void markAnswerMode(UUID runId, String answerMode, String stopReason) {
    }
    default void markRetrievalHealth(UUID runId, String retrievalHealth, int evidenceCount) {
    }
    default void applyAssistantProfile(UUID runId, UUID assistantProfileVersionId) {
    }
    boolean isCancellationRequested(UUID runId);
    void complete(UUID runId);
    void fail(UUID runId, String message);
    default void fail(UUID runId, String message, String stopReason) {
        fail(runId, message);
    }
    void cancel(UUID runId);

    record ReprocessSeed(UUID conversationId, CreateRunRequest request) {
    }
}
