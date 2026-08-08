package com.yanyue.rag.api.knowledge;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.contract.knowledge.DocumentAccessPolicyView;
import com.yanyue.rag.contract.knowledge.UpdateDocumentAccessPolicyRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/access-policy")
public class DocumentAccessController {
    private final DocumentAccessService access;

    public DocumentAccessController(DocumentAccessService access) {
        this.access = access;
    }

    @GetMapping
    public DocumentAccessPolicyView view(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID documentId
    ) {
        return access.view(user.organizationId(), user.userId(), documentId);
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public DocumentAccessPolicyView update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID documentId,
            @Valid @RequestBody UpdateDocumentAccessPolicyRequest request
    ) {
        return access.update(user.organizationId(), user.userId(), documentId, request);
    }
}
