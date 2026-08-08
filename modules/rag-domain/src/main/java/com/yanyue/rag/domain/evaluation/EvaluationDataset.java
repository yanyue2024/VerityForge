package com.yanyue.rag.domain.evaluation;

import java.time.Instant;
import java.util.UUID;

public record EvaluationDataset(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        Instant createdAt
) {
}
