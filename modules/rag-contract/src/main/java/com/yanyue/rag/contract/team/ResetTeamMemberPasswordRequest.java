package com.yanyue.rag.contract.team;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetTeamMemberPasswordRequest(
        @NotBlank @Size(min = 12, max = 200) String newPassword
) {
}
