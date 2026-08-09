package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.agent.deep.AcceptedEvidence;
import java.time.Instant;
import java.util.UUID;

public interface EvidenceValidationPort {
    boolean isCurrentlyValid(UUID organizationId, UUID userId, AcceptedEvidence evidence, Instant at);
}
