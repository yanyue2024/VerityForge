package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.model.PipelineConfig;
import java.util.Optional;
import java.util.UUID;

public interface PipelineConfigRepository {
    Optional<PipelineConfig> findActive(UUID organizationId);
    Optional<PipelineConfig> findDraft(UUID organizationId);
    Optional<PipelineConfig> findById(UUID organizationId, UUID configId);
    java.util.List<PipelineConfig> findVersions(UUID organizationId);

    PipelineConfig activate(PipelineConfig config);
    PipelineConfig saveDraft(PipelineConfig config);
    PipelineConfig publishDraft(UUID organizationId, UUID configId);
    void markDraftPreviewed(UUID organizationId, UUID configId);
    boolean isDraftPreviewed(UUID organizationId, UUID configId);
}
