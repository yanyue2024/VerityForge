package com.yanyue.rag.domain.knowledge;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Document {
    private final UUID id;
    private final UUID knowledgeBaseId;
    private final UUID organizationId;
    private String title;
    private DocumentStatus status;
    private UUID currentVersionId;
    private final Instant createdAt;
    private Instant updatedAt;

    public Document(
            UUID id,
            UUID knowledgeBaseId,
            UUID organizationId,
            String title,
            DocumentStatus status,
            UUID currentVersionId,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId);
        this.organizationId = Objects.requireNonNull(organizationId);
        this.title = requireTitle(title);
        this.status = Objects.requireNonNull(status);
        this.currentVersionId = currentVersionId;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static Document create(UUID knowledgeBaseId, UUID organizationId, String title, Instant now) {
        return new Document(UUID.randomUUID(), knowledgeBaseId, organizationId, title, DocumentStatus.ACTIVE, null, now, now);
    }

    public void publish(UUID versionId, Instant now) {
        if (status != DocumentStatus.ACTIVE) {
            throw new IllegalStateException("Only active documents can publish a version");
        }
        currentVersionId = Objects.requireNonNull(versionId);
        updatedAt = Objects.requireNonNull(now);
    }

    public void deactivate(Instant now) {
        status = DocumentStatus.INACTIVE;
        updatedAt = Objects.requireNonNull(now);
    }

    public void reactivate(Instant now) {
        if (status == DocumentStatus.DELETED) {
            throw new IllegalStateException("Deleted documents cannot be reactivated");
        }
        status = DocumentStatus.ACTIVE;
        updatedAt = Objects.requireNonNull(now);
    }

    public void delete(Instant now) {
        status = DocumentStatus.DELETED;
        updatedAt = Objects.requireNonNull(now);
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Document title is required");
        }
        return title.strip();
    }

    public UUID id() { return id; }
    public UUID knowledgeBaseId() { return knowledgeBaseId; }
    public UUID organizationId() { return organizationId; }
    public String title() { return title; }
    public DocumentStatus status() { return status; }
    public UUID currentVersionId() { return currentVersionId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
