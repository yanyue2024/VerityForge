package com.yanyue.rag.api.pipeline;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.contract.pipeline.PipelineConfigView;
import com.yanyue.rag.contract.pipeline.UpdatePipelineConfigRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pipeline-config/active")
public class PipelineConfigController {
    private final PipelineConfigService service;

    public PipelineConfigController(PipelineConfigService service) {
        this.service = service;
    }

    @GetMapping
    public PipelineConfigView active(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.active(user.organizationId());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PipelineConfigView activate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UpdatePipelineConfigRequest request
    ) {
        return service.activate(user.organizationId(), request);
    }
}
