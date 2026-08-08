package com.yanyue.rag.api.knowledge;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.contract.knowledge.MetadataSchemaView;
import com.yanyue.rag.contract.knowledge.UpdateMetadataSchemaRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metadata-schema")
public class OrganizationMetadataSchemaController {
    private final MetadataSchemaService service;

    public OrganizationMetadataSchemaController(MetadataSchemaService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<MetadataSchemaView> active(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.organizationActive(user.organizationId())
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/versions")
    public List<MetadataSchemaView> history(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.organizationHistory(user.organizationId());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public MetadataSchemaView activate(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody UpdateMetadataSchemaRequest request) {
        return service.activateForOrganization(user.organizationId(), request);
    }
}
