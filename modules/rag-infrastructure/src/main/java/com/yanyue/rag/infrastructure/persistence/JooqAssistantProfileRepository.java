package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.model.AssistantProfile;
import com.yanyue.rag.domain.port.AssistantProfileRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqAssistantProfileRepository implements AssistantProfileRepository {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqAssistantProfileRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public java.util.Optional<AssistantProfile> findPublished(UUID organizationId) {
        return find(organizationId, "PUBLISHED");
    }

    @Override
    public java.util.Optional<AssistantProfile> findDraft(UUID organizationId) {
        return find(organizationId, "DRAFT");
    }

    private java.util.Optional<AssistantProfile> find(UUID organizationId, String status) {
        return dsl.fetchOptional("""
                SELECT * FROM assistant_profile_version
                WHERE organization_id = ? AND status = ? ORDER BY version DESC LIMIT 1
                """, organizationId, status).map(this::map);
    }

    @Override
    public java.util.Optional<AssistantProfile> findById(UUID organizationId, UUID profileId) {
        return dsl.fetchOptional("SELECT * FROM assistant_profile_version WHERE organization_id = ? AND id = ?",
                organizationId, profileId).map(this::map);
    }

    @Override
    public java.util.Optional<AssistantProfile> findForConversation(UUID organizationId, UUID conversationId) {
        return dsl.fetchOptional("""
                SELECT p.* FROM conversation c
                JOIN assistant_profile_version p ON p.id = c.assistant_profile_version_id
                WHERE c.organization_id = ? AND c.id = ?
                """, organizationId, conversationId).map(this::map);
    }

    @Override
    public List<AssistantProfile> findVersions(UUID organizationId) {
        return dsl.fetch("""
                SELECT * FROM assistant_profile_version WHERE organization_id = ?
                ORDER BY version DESC
                """, organizationId).map(this::map);
    }

    @Override
    public AssistantProfile saveDraft(AssistantProfile profile) {
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            var existing = tx.fetchOptional("""
                    SELECT id, version, created_at FROM assistant_profile_version
                    WHERE organization_id = ? AND status = 'DRAFT'
                    """, profile.organizationId());
            Record record;
            if (existing.isPresent()) {
                record = tx.fetchOne("""
                        UPDATE assistant_profile_version SET assistant_name = ?, identity_text = ?,
                            capabilities = ?::jsonb, tone = ?, boundaries = ?::jsonb,
                            additional_instructions = ?, previewed_at = NULL, updated_at = now()
                        WHERE id = ? RETURNING *
                        """, profile.assistantName(), profile.identity(), json(profile.capabilities()), profile.tone(),
                        json(profile.boundaries()), profile.additionalInstructions(), existing.get().get("id", UUID.class));
            } else {
                var nextVersion = tx.fetchValue("""
                        SELECT COALESCE(max(version), 0) + 1 FROM assistant_profile_version WHERE organization_id = ?
                        """, profile.organizationId(), Integer.class);
                record = tx.fetchOne("""
                        INSERT INTO assistant_profile_version
                            (id, organization_id, version, status, assistant_name, identity_text,
                             capabilities, tone, boundaries, additional_instructions, created_at, updated_at)
                        VALUES (?, ?, ?, 'DRAFT', ?, ?, ?::jsonb, ?, ?::jsonb, ?, now(), now()) RETURNING *
                        """, profile.id(), profile.organizationId(), nextVersion, profile.assistantName(),
                        profile.identity(), json(profile.capabilities()), profile.tone(), json(profile.boundaries()),
                        profile.additionalInstructions());
            }
            return map(record);
        });
    }

    @Override
    public AssistantProfile publish(UUID organizationId, UUID profileId) {
        return dsl.transactionResult(configuration -> {
            var tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("""
                    UPDATE assistant_profile_version SET status = 'ARCHIVED', updated_at = now()
                    WHERE organization_id = ? AND status = 'PUBLISHED'
                    """, organizationId);
            var record = tx.fetchOne("""
                    UPDATE assistant_profile_version SET status = 'PUBLISHED', published_at = now(), updated_at = now()
                    WHERE organization_id = ? AND id = ? AND status = 'DRAFT' RETURNING *
                    """, organizationId, profileId);
            if (record == null) throw new IllegalArgumentException("Assistant role draft was not found");
            return map(record);
        });
    }

    @Override
    public void markPreviewed(UUID organizationId, UUID profileId) {
        dsl.execute("""
                UPDATE assistant_profile_version SET previewed_at = now(), updated_at = now()
                WHERE organization_id = ? AND id = ? AND status = 'DRAFT'
                """, organizationId, profileId);
    }

    private AssistantProfile map(Record record) {
        return new AssistantProfile(record.get("id", UUID.class), record.get("organization_id", UUID.class),
                record.get("version", Integer.class), AssistantProfile.Status.valueOf(record.get("status", String.class)),
                record.get("assistant_name", String.class), record.get("identity_text", String.class),
                strings(record.get("capabilities", JSONB.class)), record.get("tone", String.class),
                strings(record.get("boundaries", JSONB.class)), record.get("additional_instructions", String.class),
                instant(record, "previewed_at"), instant(record, "published_at"),
                instant(record, "created_at"), instant(record, "updated_at"));
    }

    private java.time.Instant instant(Record record, String field) {
        var value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private List<String> strings(JSONB value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value.data(), new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Assistant profile list is invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize assistant profile", exception);
        }
    }
}
