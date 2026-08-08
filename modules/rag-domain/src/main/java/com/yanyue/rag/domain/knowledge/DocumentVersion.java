package com.yanyue.rag.domain.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DocumentVersion {
    private final UUID id;
    private final UUID documentId;
    private final int versionNumber;
    private final String sourceName;
    private final String contentHash;
    private final Map<String, Object> metadata;
    private DocumentVersionStatus status;
    private Instant validFrom;
    private Instant validTo;
    private Instant publishedAt;

    public DocumentVersion(
            UUID id,
            UUID documentId,
            int versionNumber,
            String sourceName,
            String contentHash,
            Map<String, Object> metadata,
            DocumentVersionStatus status,
            Instant validFrom,
            Instant validTo,
            Instant publishedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.documentId = Objects.requireNonNull(documentId);
        if (versionNumber < 1) throw new IllegalArgumentException("Version number must be positive");
        this.versionNumber = versionNumber;
        this.sourceName = Objects.requireNonNull(sourceName);
        this.contentHash = Objects.requireNonNull(contentHash);
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.status = Objects.requireNonNull(status);
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.publishedAt = publishedAt;
    }

    public void startProcessing() { transition(DocumentVersionStatus.DRAFT, DocumentVersionStatus.PROCESSING); }
    public void markReady() { transition(DocumentVersionStatus.PROCESSING, DocumentVersionStatus.READY); }

    public void publish(Instant now) {
        transition(DocumentVersionStatus.READY, DocumentVersionStatus.PUBLISHED);
        publishedAt = Objects.requireNonNull(now);
    }

    public void supersede() { transition(DocumentVersionStatus.PUBLISHED, DocumentVersionStatus.SUPERSEDED); }

    public void fail() {
        if (status != DocumentVersionStatus.PROCESSING && status != DocumentVersionStatus.DRAFT) {
            throw new IllegalStateException("Only a pending version can fail");
        }
        status = DocumentVersionStatus.FAILED;
    }

    public void expire(Instant now) {
        if (status != DocumentVersionStatus.PUBLISHED) {
            throw new IllegalStateException("Only published versions can expire");
        }
        status = DocumentVersionStatus.EXPIRED;
        validTo = Objects.requireNonNull(now);
    }

    public boolean isEffectiveAt(Instant instant) {
        return status == DocumentVersionStatus.PUBLISHED
                && (validFrom == null || !instant.isBefore(validFrom))
                && (validTo == null || instant.isBefore(validTo));
    }

    private void transition(DocumentVersionStatus expected, DocumentVersionStatus target) {
        if (status != expected) {
            throw new IllegalStateException("Expected version status " + expected + " but was " + status);
        }
        status = target;
    }

    public UUID id() { return id; }
    public UUID documentId() { return documentId; }
    public int versionNumber() { return versionNumber; }
    public String sourceName() { return sourceName; }
    public String contentHash() { return contentHash; }
    public Map<String, Object> metadata() { return metadata; }
    public DocumentVersionStatus status() { return status; }
    public Instant validFrom() { return validFrom; }
    public Instant validTo() { return validTo; }
    public Instant publishedAt() { return publishedAt; }
}
