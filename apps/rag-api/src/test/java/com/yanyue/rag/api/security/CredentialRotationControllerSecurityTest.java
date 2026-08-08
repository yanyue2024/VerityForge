package com.yanyue.rag.api.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.yanyue.rag.application.security.CredentialRotationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(CredentialRotationControllerSecurityTest.TestConfiguration.class)
class CredentialRotationControllerSecurityTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Autowired
    private CredentialRotationController controller;

    @Autowired
    private CredentialRotationService service;

    @Test
    @WithMockUser(roles = "EDITOR")
    void editorCannotInspectOrRotateMasterKey() {
        assertThrows(AccessDeniedException.class, controller::status);
        assertThrows(AccessDeniedException.class, () -> controller.rotate(user("EDITOR")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void administratorCanInspectAndRotateMasterKey() {
        controller.status();
        controller.rotate(user("ADMIN"));

        verify(service).status();
        verify(service).rotate(USER_ID);
    }

    private AuthenticatedUser user(String role) {
        return new AuthenticatedUser(USER_ID, ORGANIZATION_ID, "test-user", role, 0);
    }

    @Configuration
    @EnableMethodSecurity
    static class TestConfiguration {
        @Bean
        CredentialRotationService credentialRotationService() {
            return mock(CredentialRotationService.class);
        }

        @Bean
        CredentialRotationController credentialRotationController(CredentialRotationService service) {
            return new CredentialRotationController(service);
        }
    }
}
