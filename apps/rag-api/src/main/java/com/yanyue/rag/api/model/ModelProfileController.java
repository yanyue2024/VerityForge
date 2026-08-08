package com.yanyue.rag.api.model;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.model.ModelProfileService;
import com.yanyue.rag.contract.model.CreateModelProfileRequest;
import com.yanyue.rag.contract.model.ModelProfileTestView;
import com.yanyue.rag.contract.model.ModelProfileView;
import com.yanyue.rag.contract.model.UpdateModelProfileRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/model-profiles")
@PreAuthorize("hasRole('ADMIN')")
public class ModelProfileController {
    private final ModelProfileService service;

    public ModelProfileController(ModelProfileService service) {
        this.service = service;
    }

    @GetMapping
    public List<ModelProfileView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.organizationId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelProfileView create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateModelProfileRequest request
    ) {
        return service.create(user.organizationId(), request);
    }

    @PutMapping("/{profileId}")
    public ModelProfileView update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID profileId,
            @Valid @RequestBody UpdateModelProfileRequest request
    ) {
        return service.update(user.organizationId(), profileId, request);
    }

    @PostMapping("/{profileId}/disable")
    public ModelProfileView disable(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID profileId
    ) {
        return service.disable(user.organizationId(), profileId);
    }

    @DeleteMapping("/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID profileId
    ) {
        service.delete(user.organizationId(), profileId);
    }

    @PostMapping("/{profileId}/test")
    public ModelProfileTestView test(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID profileId
    ) {
        return service.test(user.organizationId(), profileId);
    }
}
