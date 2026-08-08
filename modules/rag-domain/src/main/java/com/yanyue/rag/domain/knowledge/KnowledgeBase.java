package com.yanyue.rag.domain.knowledge;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class KnowledgeBase {
    private final UUID id;
    private final UUID organizationId;
    private String name;
    private String description;
    private ChunkPolicy chunkPolicy;
    private final Instant createdAt;
    private Instant updatedAt;

    public KnowledgeBase(
            UUID id,
            UUID organizationId,
            String name,
            String description,
            ChunkPolicy chunkPolicy,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.organizationId = Objects.requireNonNull(organizationId);
        rename(name, description);
        this.chunkPolicy = Objects.requireNonNullElseGet(chunkPolicy, ChunkPolicy::defaults);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static KnowledgeBase create(UUID organizationId, String name, String description, Instant now) {
        return new KnowledgeBase(UUID.randomUUID(), organizationId, name, description, ChunkPolicy.defaults(), now, now);
    }

    public void rename(String name, String description) {
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("Knowledge base name must contain 1-120 characters");
        }
        this.name = name.strip();
        this.description = description == null ? "" : description.strip();
    }

    public void changeChunkPolicy(ChunkPolicy chunkPolicy, Instant now) {
        this.chunkPolicy = Objects.requireNonNull(chunkPolicy);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public UUID id() { return id; }
    public UUID organizationId() { return organizationId; }
    public String name() { return name; }
    public String description() { return description; }
    public ChunkPolicy chunkPolicy() { return chunkPolicy; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
