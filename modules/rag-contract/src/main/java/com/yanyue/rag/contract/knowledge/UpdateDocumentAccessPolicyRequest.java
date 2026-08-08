package com.yanyue.rag.contract.knowledge;

import com.yanyue.rag.contract.team.TeamMemberRole;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

public record UpdateDocumentAccessPolicyRequest(
        @NotNull DocumentAccessMode mode,
        Set<TeamMemberRole> allowedRoles,
        Set<UUID> allowedUserIds
) {
    public UpdateDocumentAccessPolicyRequest {
        allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
        allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
    }
}
