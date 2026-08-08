package com.yanyue.rag.contract.team;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTeamMemberRequest(
        @NotBlank @Size(max = 120) String displayName,
        @NotNull TeamMemberRole role,
        @NotNull Boolean enabled
) {
}
