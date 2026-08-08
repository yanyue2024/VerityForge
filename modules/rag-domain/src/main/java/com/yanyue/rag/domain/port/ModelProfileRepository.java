package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.model.ModelProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelProfileRepository {
    ModelProfile save(ModelProfile profile);

    Optional<ModelProfile> findById(UUID organizationId, UUID id);

    Optional<ModelProfile> findById(UUID id);

    boolean isUsedByActiveGeneration(UUID id);

    boolean isUsedByActivePipeline(UUID id);

    List<ModelProfile> findAll(UUID organizationId);
}
