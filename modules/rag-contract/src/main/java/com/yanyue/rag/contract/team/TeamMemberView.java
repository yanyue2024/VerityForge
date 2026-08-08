package com.yanyue.rag.contract.team;

import java.time.Instant;
import java.util.UUID;

public record TeamMemberView(
        UUID id,
        String username,
        String displayName,
        TeamMemberRole role,
        boolean enabled,
        boolean currentUser,
        Instant createdAt,
        Instant updatedAt
) {
}
