package com.yanyue.rag.api.auth;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.api.security.JwtService;
import com.yanyue.rag.api.team.TeamMemberService;
import com.yanyue.rag.contract.auth.ChangePasswordRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final DSLContext dsl;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TeamMemberService members;

    public AuthController(DSLContext dsl, PasswordEncoder passwordEncoder, JwtService jwtService,
                          TeamMemberService members) {
        this.dsl = dsl;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.members = members;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        var record = dsl.fetchOptional("""
                SELECT id, organization_id, username, password_hash, display_name, role, auth_version
                FROM app_user WHERE lower(username) = lower(?) AND enabled = true
                """, request.username()).orElseThrow(() -> unauthorized());
        if (!passwordEncoder.matches(request.password(), record.get("password_hash", String.class))) {
            throw unauthorized();
        }
        var user = new AuthenticatedUser(record.get("id", UUID.class), record.get("organization_id", UUID.class),
                record.get("username", String.class), record.get("role", String.class),
                record.get("auth_version", Long.class));
        return new LoginResponse(jwtService.issue(user), jwtService.expiresAt(), user.userId(), user.organizationId(),
                record.get("display_name", String.class), user.role());
    }

    @PostMapping("/change-password")
    public LoginResponse changePassword(@AuthenticationPrincipal AuthenticatedUser user,
                                        @Valid @RequestBody ChangePasswordRequest request) {
        var changed = members.changePassword(user, request);
        return new LoginResponse(jwtService.issue(changed.user()), jwtService.expiresAt(), changed.user().userId(),
                changed.user().organizationId(), changed.displayName(), changed.user().role());
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, Instant expiresAt, UUID userId, UUID organizationId,
                                String displayName, String role) {
    }
}
