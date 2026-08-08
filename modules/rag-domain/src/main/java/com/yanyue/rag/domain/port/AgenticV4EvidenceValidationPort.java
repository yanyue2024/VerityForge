package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import java.time.Instant;
import java.util.UUID;

public interface AgenticV4EvidenceValidationPort {
    boolean isCurrentlyValid(UUID organizationId, UUID userId, AcceptedEvidence evidence, Instant at);
}
