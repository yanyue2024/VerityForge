package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.knowledge.Document;
import com.yanyue.rag.domain.knowledge.DocumentVersion;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {
    Document save(Document document);
    DocumentVersion saveVersion(DocumentVersion version);
    Optional<Document> findById(UUID organizationId, UUID documentId);
    Optional<DocumentVersion> findVersion(UUID versionId);
    void publishVersion(Document document, DocumentVersion nextVersion, DocumentVersion previousVersion);
}
