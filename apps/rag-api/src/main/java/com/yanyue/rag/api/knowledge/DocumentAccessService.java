package com.yanyue.rag.api.knowledge;

import com.yanyue.rag.contract.knowledge.DocumentAccessMode;
import com.yanyue.rag.contract.knowledge.DocumentAccessPolicyView;
import com.yanyue.rag.contract.knowledge.UpdateDocumentAccessPolicyRequest;
import com.yanyue.rag.contract.team.TeamMemberRole;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

@Service
public class DocumentAccessService {
    private final DSLContext dsl;

    public DocumentAccessService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public DocumentAccessPolicyView view(UUID organizationId, UUID userId, UUID documentId) {
        return dsl.fetchOptional("""
                SELECT d.id, d.access_mode, d.allowed_roles, d.allowed_user_ids, d.updated_at,
                       CASE
                         WHEN u.role = 'ADMIN' THEN 'ADMIN'
                         WHEN d.access_mode = 'ORGANIZATION' THEN 'ORGANIZATION'
                         WHEN u.id = ANY(d.allowed_user_ids) THEN 'USER'
                         WHEN u.role = ANY(d.allowed_roles) THEN 'ROLE'
                         ELSE 'DENIED'
                       END AS access_reason
                FROM document d
                JOIN app_user u ON u.id = ? AND u.organization_id = d.organization_id AND u.enabled = true
                WHERE d.id = ? AND d.organization_id = ? AND document_is_accessible(d.id, u.id)
                """, userId, documentId, organizationId)
                .map(this::view)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
    }

    public DocumentAccessPolicyView update(
            UUID organizationId,
            UUID actorUserId,
            UUID documentId,
            UpdateDocumentAccessPolicyRequest request
    ) {
        var roles = normalizedRoles(request);
        var userIds = normalizedUsers(request);
        return dsl.transactionResult(configuration -> {
            var tx = DSL.using(configuration);
            var current = tx.fetchOptional("""
                    SELECT id, access_mode, allowed_roles, allowed_user_ids
                    FROM document
                    WHERE id = ? AND organization_id = ? AND status <> 'DELETED'
                    FOR UPDATE
                    """, documentId, organizationId)
                    .orElseThrow(() -> new IllegalArgumentException("Document not found"));
            validateUsers(tx, organizationId, userIds);

            var previousMode = current.get("access_mode", String.class);
            var previousRoles = current.get("allowed_roles", String[].class);
            var previousUsers = current.get("allowed_user_ids", UUID[].class);
            var nextRoles = roles.stream().map(Enum::name).toArray(String[]::new);
            var nextUsers = userIds.toArray(UUID[]::new);
            boolean changed = !previousMode.equals(request.mode().name())
                    || !same(previousRoles, nextRoles)
                    || !same(previousUsers, nextUsers);
            if (changed) {
                tx.execute("""
                        UPDATE document
                        SET access_mode = ?, allowed_roles = ?, allowed_user_ids = ?, updated_at = now()
                        WHERE id = ?
                        """, request.mode().name(), nextRoles, nextUsers, documentId);
                tx.execute("""
                        INSERT INTO document_access_revision
                            (document_id, changed_by, previous_mode, new_mode,
                             previous_roles, new_roles, previous_user_ids, new_user_ids)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, documentId, actorUserId, previousMode, request.mode().name(),
                        previousRoles, nextRoles, previousUsers, nextUsers);
            }
            return view(tx, organizationId, actorUserId, documentId);
        });
    }

    private DocumentAccessPolicyView view(
            DSLContext context,
            UUID organizationId,
            UUID userId,
            UUID documentId
    ) {
        var record = context.fetchOne("""
                SELECT d.id, d.access_mode, d.allowed_roles, d.allowed_user_ids, d.updated_at,
                       CASE
                         WHEN u.role = 'ADMIN' THEN 'ADMIN'
                         WHEN d.access_mode = 'ORGANIZATION' THEN 'ORGANIZATION'
                         WHEN u.id = ANY(d.allowed_user_ids) THEN 'USER'
                         WHEN u.role = ANY(d.allowed_roles) THEN 'ROLE'
                         ELSE 'DENIED'
                       END AS access_reason
                FROM document d
                JOIN app_user u ON u.id = ? AND u.organization_id = d.organization_id AND u.enabled = true
                WHERE d.id = ? AND d.organization_id = ?
                """, userId, documentId, organizationId);
        if (record == null) throw new IllegalArgumentException("Document not found");
        return view(record);
    }

    private DocumentAccessPolicyView view(Record record) {
        var roles = Arrays.stream(record.get("allowed_roles", String[].class))
                .map(TeamMemberRole::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var userIds = new LinkedHashSet<>(Arrays.asList(record.get("allowed_user_ids", UUID[].class)));
        return new DocumentAccessPolicyView(
                record.get("id", UUID.class),
                DocumentAccessMode.valueOf(record.get("access_mode", String.class)),
                roles,
                userIds,
                record.get("access_reason", String.class),
                record.get("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private Set<TeamMemberRole> normalizedRoles(UpdateDocumentAccessPolicyRequest request) {
        if (request.mode() == DocumentAccessMode.ORGANIZATION) {
            if (!request.allowedRoles().isEmpty() || !request.allowedUserIds().isEmpty()) {
                throw new IllegalArgumentException("Organization-visible documents cannot contain restricted grants");
            }
            return Set.of();
        }
        if (request.allowedRoles().contains(TeamMemberRole.ADMIN)) {
            throw new IllegalArgumentException("Administrators already have recovery access and cannot be added as a grant");
        }
        return request.allowedRoles().stream()
                .sorted(Comparator.comparing(Enum::name))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<UUID> normalizedUsers(UpdateDocumentAccessPolicyRequest request) {
        if (request.mode() == DocumentAccessMode.ORGANIZATION) return Set.of();
        return request.allowedUserIds().stream().sorted().collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void validateUsers(DSLContext context, UUID organizationId, Set<UUID> userIds) {
        if (userIds.isEmpty()) return;
        var count = context.fetchOne("""
                SELECT count(*) AS count FROM app_user
                WHERE organization_id = ? AND id = ANY(?::uuid[])
                """, organizationId, userIds.toArray(UUID[]::new)).get("count", Long.class);
        if (count == null || count != userIds.size()) {
            throw new IllegalArgumentException("Every allowed member must belong to the current organization");
        }
    }

    private <T> boolean same(T[] left, T[] right) {
        return new LinkedHashSet<>(Arrays.asList(left)).equals(new LinkedHashSet<>(Arrays.asList(right)));
    }
}
