package com.yanyue.rag.api.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.api.security.JwtAuthenticationFilter;
import com.yanyue.rag.api.security.JwtService;
import com.yanyue.rag.contract.auth.ChangePasswordRequest;
import com.yanyue.rag.contract.team.CreateTeamMemberRequest;
import com.yanyue.rag.contract.team.ResetTeamMemberPasswordRequest;
import com.yanyue.rag.contract.team.TeamMemberRole;
import com.yanyue.rag.contract.team.UpdateTeamMemberRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

class TeamMemberServiceIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);
    private static PostgreSQLContainer postgres;
    private static DSLContext dsl;
    private static Argon2PasswordEncoder passwordEncoder;

    private UUID organizationId;
    private UUID administratorId;
    private TeamMemberService service;
    private JwtService jwt;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        administratorId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "team-" + organizationId);
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, 'admin', ?, 'Administrator', 'ADMIN')
                """, administratorId, organizationId, passwordEncoder.encode("CurrentAdmin123!"));
        service = new TeamMemberService(dsl, passwordEncoder);
        jwt = new JwtService(new ObjectMapper(), CLOCK, "integration-test-secret", 3600);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsScopedMemberAndNeverStoresPlaintextPassword() {
        var member = service.create(organizationId, administratorId, new CreateTeamMemberRequest(
                "editor.one", "Editor One", TeamMemberRole.EDITOR, "EditorPassword123!"));
        var otherOrganization = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, 'other-team')", otherOrganization);
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, 'hidden', 'not-a-real-hash', 'Hidden User', 'VIEWER')
                """, UUID.randomUUID(), otherOrganization);

        var storedHash = dsl.fetchOne(
                "SELECT password_hash FROM app_user WHERE id = ?", member.id()).get(0, String.class);
        assertNotEquals("EditorPassword123!", storedHash);
        assertTrue(passwordEncoder.matches("EditorPassword123!", storedHash));
        assertEquals(2, service.list(organizationId, administratorId).size());
        assertTrue(service.list(organizationId, administratorId).stream()
                .noneMatch(value -> value.username().equals("hidden")));
        assertTrue(member.enabled());
        assertEquals(TeamMemberRole.EDITOR, member.role());
        var duplicate = assertThrows(ResponseStatusException.class, () -> service.create(
                organizationId, administratorId, new CreateTeamMemberRequest(
                        "Editor.One", "Duplicate", TeamMemberRole.VIEWER, "DuplicatePassword123!")));
        assertEquals(409, duplicate.getStatusCode().value());
    }

    @Test
    void roleDisableAndPasswordChangesImmediatelyRevokeTokens() throws Exception {
        var member = service.create(organizationId, administratorId, new CreateTeamMemberRequest(
                "operator", "Operator", TeamMemberRole.EDITOR, "OperatorPassword123!"));
        var editor = currentUser(member.id());
        var editorToken = jwt.issue(editor);
        assertEquals("ROLE_EDITOR", authenticate(editorToken));

        service.update(organizationId, administratorId, member.id(),
                new UpdateTeamMemberRequest("Operator", TeamMemberRole.VIEWER, true));
        assertNull(authenticate(editorToken));
        var viewer = currentUser(member.id());
        var viewerToken = jwt.issue(viewer);
        assertEquals("ROLE_VIEWER", authenticate(viewerToken));

        service.resetPassword(organizationId, member.id(),
                new ResetTeamMemberPasswordRequest("ReplacementPassword123!"));
        assertNull(authenticate(viewerToken));
        var currentHash = dsl.fetchOne(
                "SELECT password_hash FROM app_user WHERE id = ?", member.id()).get(0, String.class);
        assertTrue(passwordEncoder.matches("ReplacementPassword123!", currentHash));

        var afterReset = currentUser(member.id());
        var resetToken = jwt.issue(afterReset);
        service.update(organizationId, administratorId, member.id(),
                new UpdateTeamMemberRequest("Operator", TeamMemberRole.VIEWER, false));
        assertNull(authenticate(resetToken));
    }

    @Test
    void protectsCurrentAdministratorAndAllowsSafeDelegation() {
        var selfDemotion = assertThrows(ResponseStatusException.class, () -> service.update(
                organizationId, administratorId, administratorId,
                new UpdateTeamMemberRequest("Administrator", TeamMemberRole.EDITOR, true)));
        assertEquals(409, selfDemotion.getStatusCode().value());

        var secondAdmin = service.create(organizationId, administratorId, new CreateTeamMemberRequest(
                "backup.admin", "Backup Admin", TeamMemberRole.ADMIN, "BackupAdminPassword123!"));
        var delegated = service.update(organizationId, administratorId, secondAdmin.id(),
                new UpdateTeamMemberRequest("Backup Admin", TeamMemberRole.EDITOR, true));
        assertEquals(TeamMemberRole.EDITOR, delegated.role());
        assertEquals(1, service.list(organizationId, administratorId).stream()
                .filter(value -> value.enabled() && value.role() == TeamMemberRole.ADMIN).count());

        var selfDisable = assertThrows(ResponseStatusException.class, () -> service.update(
                organizationId, administratorId, administratorId,
                new UpdateTeamMemberRequest("Administrator", TeamMemberRole.ADMIN, false)));
        assertEquals(409, selfDisable.getStatusCode().value());
    }

    @Test
    void changesOwnPasswordAndReturnsAUsableNewSessionVersion() throws Exception {
        var original = currentUser(administratorId);
        var originalToken = jwt.issue(original);
        assertNotNull(authenticate(originalToken));

        var changed = service.changePassword(original,
                new ChangePasswordRequest("CurrentAdmin123!", "NewAdministratorPassword123!"));

        assertEquals(original.authVersion() + 1, changed.user().authVersion());
        assertNull(authenticate(originalToken));
        assertEquals("ROLE_ADMIN", authenticate(jwt.issue(changed.user())));
        var storedHash = dsl.fetchOne(
                "SELECT password_hash FROM app_user WHERE id = ?", administratorId).get(0, String.class);
        assertTrue(passwordEncoder.matches("NewAdministratorPassword123!", storedHash));
        assertFalse(passwordEncoder.matches("CurrentAdmin123!", storedHash));

        var incorrect = assertThrows(ResponseStatusException.class, () -> service.changePassword(
                changed.user(), new ChangePasswordRequest("wrong-password", "AnotherPassword123!")));
        assertEquals(400, incorrect.getStatusCode().value());
    }

    private AuthenticatedUser currentUser(UUID userId) {
        var record = dsl.fetchOne("""
                SELECT organization_id, username, role, auth_version
                FROM app_user WHERE id = ?
                """, userId);
        return new AuthenticatedUser(userId, record.get("organization_id", UUID.class),
                record.get("username", String.class), record.get("role", String.class),
                record.get("auth_version", Long.class));
    }

    private String authenticate(String token) throws Exception {
        SecurityContextHolder.clearContext();
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        new JwtAuthenticationFilter(jwt, dsl).doFilter(
                request, new MockHttpServletResponse(), new MockFilterChain());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getAuthorities().iterator().next().getAuthority();
    }
}
