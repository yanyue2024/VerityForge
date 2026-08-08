package com.yanyue.rag.api.knowledge;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.knowledge.KnowledgeBaseService;
import com.yanyue.rag.contract.knowledge.CreateKnowledgeBaseRequest;
import com.yanyue.rag.contract.knowledge.KnowledgeBaseView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    public List<KnowledgeBaseView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.organizationId(), user.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    public KnowledgeBaseView create(@AuthenticationPrincipal AuthenticatedUser user,
                                    @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return service.create(user.organizationId(), request);
    }

    @DeleteMapping("/{knowledgeBaseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user,
                       @PathVariable UUID knowledgeBaseId) {
        service.delete(user.organizationId(), knowledgeBaseId);
    }
}
