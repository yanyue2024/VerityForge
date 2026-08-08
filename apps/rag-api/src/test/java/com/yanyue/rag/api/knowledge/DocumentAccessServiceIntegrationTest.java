package com.yanyue.rag.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yanyue.rag.contract.knowledge.DocumentAccessMode;
import com.yanyue.rag.contract.knowledge.UpdateDocumentAccessPolicyRequest;
import com.yanyue.rag.contract.team.TeamMemberRole;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DocumentAccessServiceIntegrationTest {
    private static PostgreSQLContainer postgres;
    private static DSLContext dsl;

    private UUID organizationId;
    private UUID otherOrganizationId;
    private UUID administratorId;
    private UUID editorOneId;
    private UUID editorTwoId;
    private UUID viewerId;
    private UUID foreignUserId;
    private UUID documentId;
    private DocumentAccessService service;

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
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        otherOrganizationId = UUID.randomUUID();
        administratorId = user(organizationId, "admin", "ADMIN");
        editorOneId = user(organizationId, "editor-one", "EDITOR");
        editorTwoId = user(organizationId, "editor-two", "EDITOR");
        viewerId = user(organizationId, "viewer", "VIEWER");
        foreignUserId = user(otherOrganizationId, "foreign", "VIEWER");
        var knowledgeBaseId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        var now = OffsetDateTime.now();
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, description, chunk_policy, created_at, updated_at)
                VALUES (?, ?, ?, '', '{}'::jsonb, ?::timestamptz, ?::timestamptz)
                """, knowledgeBaseId, organizationId, "kb-" + knowledgeBaseId, now, now);
        dsl.execute("""
                INSERT INTO document
                    (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Restricted handbook', 'ACTIVE', ?::timestamptz, ?::timestamptz)
                """, documentId, knowledgeBaseId, organizationId, now, now);
        service = new DocumentAccessService(dsl);
    }

    @Test
    void defaultsToOrganizationVisibilityAndDeniesForeignUsers() {
        assertThat(accessible(administratorId)).isTrue();
        assertThat(accessible(editorOneId)).isTrue();
        assertThat(accessible(viewerId)).isTrue();
        assertThat(accessible(foreignUserId)).isFalse();

        var policy = service.view(organizationId, editorOneId, documentId);
        assertThat(policy.mode()).isEqualTo(DocumentAccessMode.ORGANIZATION);
        assertThat(policy.accessReason()).isEqualTo("ORGANIZATION");
        assertThat(policy.allowedRoles()).isEmpty();
        assertThat(policy.allowedUserIds()).isEmpty();
    }

    @Test
    void appliesRoleAndMemberGrantsImmediatelyAndRetainsAdminRecovery() {
        var restricted = service.update(organizationId, administratorId, documentId,
                new UpdateDocumentAccessPolicyRequest(
                        DocumentAccessMode.RESTRICTED,
                        Set.of(TeamMemberRole.VIEWER),
                        Set.of(editorOneId)));

        assertThat(restricted.mode()).isEqualTo(DocumentAccessMode.RESTRICTED);
        assertThat(restricted.allowedRoles()).containsExactly(TeamMemberRole.VIEWER);
        assertThat(restricted.allowedUserIds()).containsExactly(editorOneId);
        assertThat(accessible(administratorId)).isTrue();
        assertThat(accessible(editorOneId)).isTrue();
        assertThat(accessible(editorTwoId)).isFalse();
        assertThat(accessible(viewerId)).isTrue();

        dsl.execute("UPDATE app_user SET enabled = false WHERE id = ?", viewerId);
        assertThat(accessible(viewerId)).isFalse();

        service.update(organizationId, administratorId, documentId,
                new UpdateDocumentAccessPolicyRequest(DocumentAccessMode.RESTRICTED, Set.of(), Set.of()));
        assertThat(accessible(administratorId)).isTrue();
        assertThat(accessible(editorOneId)).isFalse();
        assertThat(accessible(editorTwoId)).isFalse();
        assertThat(dsl.fetchOne(
                "SELECT count(*) AS count FROM document_access_revision WHERE document_id = ?", documentId)
                .get("count", Long.class)).isEqualTo(2L);
    }

    @Test
    void validatesGrantBoundariesAndClearsGrantsForOrganizationMode() {
        assertThatThrownBy(() -> service.update(organizationId, administratorId, documentId,
                new UpdateDocumentAccessPolicyRequest(
                        DocumentAccessMode.RESTRICTED, Set.of(), Set.of(foreignUserId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current organization");
        assertThatThrownBy(() -> service.update(organizationId, administratorId, documentId,
                new UpdateDocumentAccessPolicyRequest(
                        DocumentAccessMode.RESTRICTED, Set.of(TeamMemberRole.ADMIN), Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recovery access");
        assertThatThrownBy(() -> service.update(organizationId, administratorId, documentId,
                new UpdateDocumentAccessPolicyRequest(
                        DocumentAccessMode.ORGANIZATION, Set.of(TeamMemberRole.VIEWER), Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot contain");
    }

    private UUID user(UUID organizationId, String username, String role) {
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?) ON CONFLICT DO NOTHING",
                organizationId, "org-" + organizationId);
        var userId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO app_user
                    (id, organization_id, username, password_hash, display_name, role)
                VALUES (?, ?, ?, 'test-hash', ?, ?)
                """, userId, organizationId, username, username, role);
        return userId;
    }

    private boolean accessible(UUID userId) {
        return Boolean.TRUE.equals(dsl.fetchOne(
                "SELECT document_is_accessible(?, ?) AS accessible", documentId, userId)
                .get("accessible", Boolean.class));
    }
}
