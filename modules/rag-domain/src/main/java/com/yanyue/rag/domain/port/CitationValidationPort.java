package com.yanyue.rag.domain.port;

import java.time.Instant;
import java.util.UUID;

public interface CitationValidationPort {
    boolean isCurrentlyValid(UUID organizationId, UUID userId, RetrievalHit hit, Instant at);
}
