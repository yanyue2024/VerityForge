package com.yanyue.rag.api.pipeline;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.pipeline.AiConfigService;
import com.yanyue.rag.contract.pipeline.AiConfigDraftRequest;
import com.yanyue.rag.contract.pipeline.AiConfigPreviewRequest;
import com.yanyue.rag.contract.pipeline.AiConfigPreviewView;
import com.yanyue.rag.contract.pipeline.AiConfigVersionView;
import com.yanyue.rag.contract.pipeline.AiConfigView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-config")
@PreAuthorize("hasRole('ADMIN')")
public class AiConfigController {
    private final AiConfigService service;

    public AiConfigController(AiConfigService service) {
        this.service = service;
    }

    @GetMapping
    public AiConfigView get(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.get(user.organizationId());
    }

    @PutMapping("/draft")
    public AiConfigView saveDraft(@AuthenticationPrincipal AuthenticatedUser user,
                                  @Valid @RequestBody AiConfigDraftRequest request) {
        return service.saveDraft(user.organizationId(), request);
    }

    @PostMapping("/draft/preview")
    public AiConfigPreviewView preview(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody AiConfigPreviewRequest request) {
        return service.preview(user.organizationId(), request.query());
    }

    @PostMapping("/draft/publish")
    public AiConfigView publish(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.publish(user.organizationId());
    }

    @PostMapping("/language-models/{profileId}/activate")
    public AiConfigView activateLanguageModel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID profileId
    ) {
        return service.activateLanguageModel(user.organizationId(), profileId);
    }

    @GetMapping("/versions")
    public List<AiConfigVersionView> versions(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.versions(user.organizationId());
    }

    @PostMapping("/versions/{versionId}/restore")
    public AiConfigView restore(@AuthenticationPrincipal AuthenticatedUser user,
                                @PathVariable UUID versionId) {
        return service.restore(user.organizationId(), versionId);
    }
}
