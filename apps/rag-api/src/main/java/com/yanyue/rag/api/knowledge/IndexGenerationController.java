package com.yanyue.rag.api.knowledge;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.knowledge.IndexGenerationService;
import com.yanyue.rag.contract.knowledge.IndexGenerationView;
import com.yanyue.rag.contract.knowledge.StartIndexRebuildRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/index-generations")
public class IndexGenerationController {
    private final IndexGenerationService service;

    public IndexGenerationController(IndexGenerationService service) {
        this.service = service;
    }

    @GetMapping
    public List<IndexGenerationView> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID knowledgeBaseId
    ) {
        return service.list(user.organizationId(), knowledgeBaseId);
    }

    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public IndexGenerationView rebuild(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody StartIndexRebuildRequest request
    ) {
        return service.rebuild(user.organizationId(), knowledgeBaseId, request);
    }

    @PostMapping("/{generationId}/activate")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public IndexGenerationView activate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID generationId
    ) {
        return service.activate(user.organizationId(), knowledgeBaseId, generationId);
    }
}
