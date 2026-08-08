package com.yanyue.rag.contract.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssistantProfileView(
        UUID id, int version, String status, String assistantName, String identity,
        List<String> capabilities, String tone, List<String> boundaries,
        String additionalInstructions, Instant previewedAt, Instant publishedAt,
        Instant createdAt, Instant updatedAt
) { }
