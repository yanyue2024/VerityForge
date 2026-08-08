package com.yanyue.rag.api.team;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.contract.team.CreateTeamMemberRequest;
import com.yanyue.rag.contract.team.ResetTeamMemberPasswordRequest;
import com.yanyue.rag.contract.team.TeamMemberView;
import com.yanyue.rag.contract.team.UpdateTeamMemberRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/team/members")
@PreAuthorize("hasRole('ADMIN')")
public class TeamMemberController {
    private final TeamMemberService members;

    public TeamMemberController(TeamMemberService members) {
        this.members = members;
    }

    @GetMapping
    public List<TeamMemberView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return members.list(user.organizationId(), user.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamMemberView create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateTeamMemberRequest request
    ) {
        return members.create(user.organizationId(), user.userId(), request);
    }

    @PutMapping("/{memberId}")
    public TeamMemberView update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID memberId,
            @Valid @RequestBody UpdateTeamMemberRequest request
    ) {
        return members.update(user.organizationId(), user.userId(), memberId, request);
    }

    @PostMapping("/{memberId}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID memberId,
            @Valid @RequestBody ResetTeamMemberPasswordRequest request
    ) {
        members.resetPassword(user.organizationId(), memberId, request);
    }
}
