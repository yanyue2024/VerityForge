package com.yanyue.rag.api.knowledge;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.knowledge.DocumentMetadataService;
import com.yanyue.rag.contract.knowledge.DocumentMetadataRevisionView;
import com.yanyue.rag.contract.knowledge.UpdateDocumentMetadataRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/metadata")
public class DocumentMetadataController {
    private final DocumentMetadataService service;

    public DocumentMetadataController(DocumentMetadataService service) {
        this.service = service;
    }

    @PatchMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public DocumentMetadataRevisionView update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID versionId,
            @Valid @RequestBody UpdateDocumentMetadataRequest request
    ) {
        return service.update(user.organizationId(), user.userId(), versionId, request);
    }
}
