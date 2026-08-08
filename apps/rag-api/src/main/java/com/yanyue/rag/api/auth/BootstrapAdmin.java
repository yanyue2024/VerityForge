package com.yanyue.rag.api.auth;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdmin implements ApplicationRunner {
    private static final UUID DEFAULT_ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final DSLContext dsl;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String displayName;

    public BootstrapAdmin(DSLContext dsl, PasswordEncoder passwordEncoder,
                          @Value("${rag.bootstrap.admin-username}") String username,
                          @Value("${rag.bootstrap.admin-password}") String password,
                          @Value("${rag.bootstrap.admin-display-name}") String displayName) {
        this.dsl = dsl;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        dsl.execute("""
                INSERT INTO app_user (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, ?, ?, 'ADMIN')
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), DEFAULT_ORGANIZATION, username, passwordEncoder.encode(password), displayName);
    }
}
