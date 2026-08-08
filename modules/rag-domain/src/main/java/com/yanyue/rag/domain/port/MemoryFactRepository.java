package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.model.MemoryFact;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

public interface MemoryFactRepository {
    MemoryFact save(MemoryFact fact);

    Optional<MemoryFact> find(UUID organizationId, UUID userId, UUID factId);

    List<MemoryFact> findAll(UUID organizationId, UUID userId);

    List<MemoryFact> findConfirmedActive(UUID organizationId, UUID userId, Instant at, int limit);

    boolean sourceMessageBelongsTo(UUID organizationId, UUID userId, UUID sourceMessageId);

    boolean delete(UUID organizationId, UUID userId, UUID factId);
}
