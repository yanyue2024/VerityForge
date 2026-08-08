package com.yanyue.rag.api.security;

import com.yanyue.rag.application.security.CredentialRotationService;
import com.yanyue.rag.contract.security.CredentialRotationStatusView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/security/credential-rotation")
@PreAuthorize("hasRole('ADMIN')")
public class CredentialRotationController {
    private final CredentialRotationService service;

    public CredentialRotationController(CredentialRotationService service) {
        this.service = service;
    }

    @GetMapping
    public CredentialRotationStatusView status() {
        return service.status();
    }

    @PostMapping
    public CredentialRotationStatusView rotate(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.rotate(user.userId());
    }
}
