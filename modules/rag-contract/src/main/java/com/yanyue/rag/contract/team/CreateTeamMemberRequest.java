package com.yanyue.rag.contract.team;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTeamMemberRequest(
        @NotBlank
        @Pattern(regexp = "^[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{2,79}$",
                message = "Username must be 3-80 letters, numbers, dots, underscores, or hyphens")
        String username,
        @NotBlank @Size(max = 120) String displayName,
        @NotNull TeamMemberRole role,
        @NotBlank @Size(min = 12, max = 200) String password
) {
}
