package com.yanyue.rag.contract.pipeline;

import java.time.Instant;
import java.util.UUID;

public record AiConfigVersionView(
        UUID id, String kind, int version, String status, String name, Instant createdAt, Instant publishedAt
) { }
