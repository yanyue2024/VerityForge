package com.yanyue.rag.api.team;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.api.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(TeamMemberControllerSecurityTest.TestConfiguration.class)
class TeamMemberControllerSecurityTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Autowired
    private TeamMemberController controller;

    @Autowired
    private TeamMemberService service;

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotInvokeTeamAdministration() {
        assertThrows(AccessDeniedException.class, () -> controller.list(user("VIEWER")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void administratorCanInvokeTeamAdministration() {
        when(service.list(ORGANIZATION_ID, USER_ID)).thenReturn(List.of());

        controller.list(user("ADMIN"));

        verify(service).list(ORGANIZATION_ID, USER_ID);
    }

    private AuthenticatedUser user(String role) {
        return new AuthenticatedUser(USER_ID, ORGANIZATION_ID, "test-user", role, 0);
    }

    @Configuration
    @EnableMethodSecurity
    static class TestConfiguration {
        @Bean
        TeamMemberService teamMemberService() {
            return mock(TeamMemberService.class);
        }

        @Bean
        TeamMemberController teamMemberController(TeamMemberService service) {
            return new TeamMemberController(service);
        }
    }
}
