package com.yanyue.rag.application.knowledge;

import com.yanyue.rag.contract.knowledge.DocumentMetadataRevisionView;
import com.yanyue.rag.contract.knowledge.UpdateDocumentMetadataRequest;
import com.yanyue.rag.domain.port.DocumentMetadataPort;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentMetadataService {
    private final DocumentMetadataPort metadata;
    private final MetadataSchemaService schemas;

    public DocumentMetadataService(DocumentMetadataPort metadata, MetadataSchemaService schemas) {
        this.metadata = metadata;
        this.schemas = schemas;
    }

    @Transactional
    public DocumentMetadataRevisionView update(
            UUID organizationId,
            UUID userId,
            UUID versionId,
            UpdateDocumentMetadataRequest request
    ) {
        var context = metadata.findContext(organizationId, userId, versionId)
                .orElseThrow(() -> new IllegalArgumentException("Document version not found"));
        if (!context.current() || !"PUBLISHED".equals(context.status())) {
            throw new IllegalArgumentException("Only the current published version supports metadata-only updates");
        }
        if (request.validFrom() != null && request.validTo() != null
                && !request.validTo().isAfter(request.validFrom())) {
            throw new IllegalArgumentException("validTo must be later than validFrom");
        }
        schemas.validateMetadata(organizationId, context.knowledgeBaseId(), request.metadata());
        var revision = metadata.update(organizationId, userId, versionId, request.metadata(),
                request.validFrom(), request.validTo());
        return new DocumentMetadataRevisionView(
                revision.revisionId(), revision.documentVersionId(), revision.metadata(), revision.validFrom(),
                revision.validTo(), false, revision.createdAt()
        );
    }
}
