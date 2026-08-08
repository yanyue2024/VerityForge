package com.yanyue.rag.api.team;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.contract.auth.ChangePasswordRequest;
import com.yanyue.rag.contract.team.CreateTeamMemberRequest;
import com.yanyue.rag.contract.team.ResetTeamMemberPasswordRequest;
import com.yanyue.rag.contract.team.TeamMemberRole;
import com.yanyue.rag.contract.team.TeamMemberView;
import com.yanyue.rag.contract.team.UpdateTeamMemberRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeamMemberService {
    private final DSLContext dsl;
    private final PasswordEncoder passwordEncoder;

    public TeamMemberService(DSLContext dsl, PasswordEncoder passwordEncoder) {
        this.dsl = dsl;
        this.passwordEncoder = passwordEncoder;
    }

    public List<TeamMemberView> list(UUID organizationId, UUID currentUserId) {
        return dsl.fetch("""
                SELECT id, username, display_name, role, enabled, created_at, updated_at
                FROM app_user
                WHERE organization_id = ?
                ORDER BY enabled DESC, role, lower(display_name), created_at
                """, organizationId).map(record -> view(record, currentUserId));
    }

    public TeamMemberView create(UUID organizationId, UUID currentUserId, CreateTeamMemberRequest request) {
        var username = request.username().strip();
        var displayName = request.displayName().strip();
        var record = dsl.fetchOptional("""
                INSERT INTO app_user (
                    id, organization_id, username, password_hash, display_name, role, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, true)
                ON CONFLICT DO NOTHING
                RETURNING id, username, display_name, role, enabled, created_at, updated_at
                """, UUID.randomUUID(), organizationId, username, passwordEncoder.encode(request.password()),
                displayName, request.role().name()).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "Username already exists in this team"));
        return view(record, currentUserId);
    }

    public TeamMemberView update(
            UUID organizationId,
            UUID currentUserId,
            UUID memberId,
            UpdateTeamMemberRequest request
    ) {
        return dsl.transactionResult(configuration -> {
            var tx = DSL.using(configuration);
            var existing = tx.fetchOptional("""
                    SELECT id, role, enabled
                    FROM app_user
                    WHERE id = ? AND organization_id = ?
                    FOR UPDATE
                    """, memberId, organizationId).orElseThrow(() -> notFound());

            if (memberId.equals(currentUserId)
                    && (!request.enabled() || request.role() != TeamMemberRole.ADMIN)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "You cannot disable or remove your own administrator role");
            }

            var currentlyAdmin = "ADMIN".equals(existing.get("role", String.class))
                    && Boolean.TRUE.equals(existing.get("enabled", Boolean.class));
            var remainsAdmin = request.enabled() && request.role() == TeamMemberRole.ADMIN;
            if (currentlyAdmin && !remainsAdmin) {
                var administrators = tx.fetch("""
                        SELECT id FROM app_user
                        WHERE organization_id = ? AND enabled = true AND role = 'ADMIN'
                        FOR UPDATE
                        """, organizationId);
                if (administrators.size() <= 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The team must retain at least one enabled administrator");
                }
            }

            var record = tx.fetchOne("""
                    UPDATE app_user
                    SET display_name = ?, role = ?, enabled = ?, updated_at = now(),
                        auth_version = auth_version
                            + CASE WHEN role <> ? OR enabled <> ? THEN 1 ELSE 0 END
                    WHERE id = ? AND organization_id = ?
                    RETURNING id, username, display_name, role, enabled, created_at, updated_at
                    """, request.displayName().strip(), request.role().name(), request.enabled(),
                    request.role().name(), request.enabled(), memberId, organizationId);
            return view(record, currentUserId);
        });
    }

    public void resetPassword(
            UUID organizationId,
            UUID memberId,
            ResetTeamMemberPasswordRequest request
    ) {
        var updated = dsl.execute("""
                UPDATE app_user
                SET password_hash = ?, auth_version = auth_version + 1, updated_at = now()
                WHERE id = ? AND organization_id = ?
                """, passwordEncoder.encode(request.newPassword()), memberId, organizationId);
        if (updated == 0) throw notFound();
    }

    public ChangedPassword changePassword(AuthenticatedUser caller, ChangePasswordRequest request) {
        return dsl.transactionResult(configuration -> {
            var tx = DSL.using(configuration);
            var existing = tx.fetchOptional("""
                    SELECT password_hash
                    FROM app_user
                    WHERE id = ? AND organization_id = ? AND enabled = true
                    FOR UPDATE
                    """, caller.userId(), caller.organizationId()).orElseThrow(() -> notFound());
            var currentHash = existing.get("password_hash", String.class);
            if (!passwordEncoder.matches(request.currentPassword(), currentHash)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
            }
            if (passwordEncoder.matches(request.newPassword(), currentHash)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "New password must be different from the current password");
            }
            var updated = tx.fetchOne("""
                    UPDATE app_user
                    SET password_hash = ?, auth_version = auth_version + 1, updated_at = now()
                    WHERE id = ? AND organization_id = ?
                    RETURNING username, display_name, role, auth_version
                    """, passwordEncoder.encode(request.newPassword()), caller.userId(), caller.organizationId());
            var user = new AuthenticatedUser(caller.userId(), caller.organizationId(),
                    updated.get("username", String.class), updated.get("role", String.class),
                    updated.get("auth_version", Long.class));
            return new ChangedPassword(user, updated.get("display_name", String.class));
        });
    }

    private TeamMemberView view(Record record, UUID currentUserId) {
        return new TeamMemberView(
                record.get("id", UUID.class),
                record.get("username", String.class),
                record.get("display_name", String.class),
                TeamMemberRole.valueOf(record.get("role", String.class)),
                Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
                currentUserId.equals(record.get("id", UUID.class)),
                record.get("created_at", OffsetDateTime.class).toInstant(),
                record.get("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Team member not found");
    }

    public record ChangedPassword(AuthenticatedUser user, String displayName) {
    }
}
