package com.yanyue.rag.api.knowledge;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.contract.knowledge.MetadataSchemaView;
import com.yanyue.rag.contract.knowledge.UpdateMetadataSchemaRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/metadata-schema")
public class MetadataSchemaController {
    private final MetadataSchemaService service;

    public MetadataSchemaController(MetadataSchemaService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<MetadataSchemaView> active(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID knowledgeBaseId
    ) {
        return service.active(user.organizationId(), knowledgeBaseId)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/versions")
    public List<MetadataSchemaView> history(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID knowledgeBaseId
    ) {
        return service.history(user.organizationId(), knowledgeBaseId);
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public MetadataSchemaView activate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody UpdateMetadataSchemaRequest request
    ) {
        return service.activate(user.organizationId(), knowledgeBaseId, request);
    }

    @DeleteMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public ResponseEntity<Void> deactivate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID knowledgeBaseId
    ) {
        return service.deactivate(user.organizationId(), knowledgeBaseId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
