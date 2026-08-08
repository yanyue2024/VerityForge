package com.yanyue.rag.api.knowledge;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.knowledge.MetadataFilterOptionsService;
import com.yanyue.rag.contract.knowledge.MetadataFilterOptionsView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metadata-filter-options")
public class MetadataFilterOptionsController {
    private final MetadataFilterOptionsService service;

    public MetadataFilterOptionsController(MetadataFilterOptionsService service) {
        this.service = service;
    }

    @GetMapping
    public MetadataFilterOptionsView options(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) List<UUID> knowledgeBaseIds
    ) {
        return service.options(user.organizationId(), user.userId(), knowledgeBaseIds);
    }
}
