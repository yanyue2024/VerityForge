package com.yanyue.rag.api.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID organizationId, String username, String role, long authVersion) {
}
