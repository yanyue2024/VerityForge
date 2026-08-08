package com.yanyue.rag.contract.knowledge;

import com.yanyue.rag.contract.team.TeamMemberRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record DocumentAccessPolicyView(
        UUID documentId,
        DocumentAccessMode mode,
        Set<TeamMemberRole> allowedRoles,
        Set<UUID> allowedUserIds,
        String accessReason,
        Instant updatedAt
) {
    public DocumentAccessPolicyView {
        allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
        allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
    }
}
