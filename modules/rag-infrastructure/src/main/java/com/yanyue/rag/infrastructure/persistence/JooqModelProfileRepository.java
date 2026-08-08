package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.model.ModelProfileTestStatus;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqModelProfileRepository implements ModelProfileRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqModelProfileRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelProfile save(ModelProfile profile) {
        dsl.execute("""
                INSERT INTO model_profile
                    (id, organization_id, profile_type, provider, name, model_name, base_url,
                     encrypted_api_key, settings, enabled, test_status, last_tested_at,
                     last_test_message, capabilities, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::timestamptz, ?, ?::jsonb,
                        ?::timestamptz, ?::timestamptz)
                ON CONFLICT (id) DO UPDATE SET
                    provider = EXCLUDED.provider,
                    name = EXCLUDED.name,
                    model_name = EXCLUDED.model_name,
                    base_url = EXCLUDED.base_url,
                    encrypted_api_key = EXCLUDED.encrypted_api_key,
                    settings = EXCLUDED.settings,
                    enabled = EXCLUDED.enabled,
                    test_status = EXCLUDED.test_status,
                    last_tested_at = EXCLUDED.last_tested_at,
                    last_test_message = EXCLUDED.last_test_message,
                    capabilities = EXCLUDED.capabilities,
                    updated_at = EXCLUDED.updated_at
                """,
                profile.id(), profile.organizationId(), profile.profileType().name(), profile.provider().name(),
                profile.name(), profile.modelName(), profile.baseUrl(), encryptedBytes(profile.encryptedApiKey()),
                json(profile.settings()), profile.enabled(), profile.testStatus().name(), offset(profile.lastTestedAt()),
                profile.lastTestMessage(), json(profile.capabilities()), offset(profile.createdAt()),
                offset(profile.updatedAt()));
        return findById(profile.organizationId(), profile.id())
                .orElseThrow(() -> new IllegalStateException("Saved model profile could not be loaded"));
    }

    @Override
    public Optional<ModelProfile> findById(UUID organizationId, UUID id) {
        return dsl.fetchOptional("""
                SELECT id, organization_id, profile_type, provider, name, model_name, base_url,
                       encrypted_api_key, settings::text AS settings, enabled, test_status,
                       last_tested_at, last_test_message, capabilities::text AS capabilities,
                       created_at, updated_at
                FROM model_profile
                WHERE organization_id = ? AND id = ?
                """, organizationId, id).map(this::map);
    }

    @Override
    public Optional<ModelProfile> findById(UUID id) {
        return dsl.fetchOptional("""
                SELECT id, organization_id, profile_type, provider, name, model_name, base_url,
                       encrypted_api_key, settings::text AS settings, enabled, test_status,
                       last_tested_at, last_test_message, capabilities::text AS capabilities,
                       created_at, updated_at
                FROM model_profile
                WHERE id = ?
                """, id).map(this::map);
    }

    @Override
    public boolean isUsedByActiveGeneration(UUID id) {
        return dsl.fetchExists(dsl.selectOne().from("index_generation")
                .where(org.jooq.impl.DSL.field("embedding_profile_id").eq(id))
                .and(org.jooq.impl.DSL.field("status").in("BUILDING", "ACTIVE", "RETIRED")));
    }

    @Override
    public boolean isUsedByActivePipeline(UUID id) {
        return dsl.fetchExists(dsl.selectOne().from("pipeline_config")
                .where(org.jooq.impl.DSL.field("active").eq(true))
                .and(org.jooq.impl.DSL.field("chat_profile_id").eq(id)
                        .or(org.jooq.impl.DSL.field("query_rewrite_profile_id").eq(id))
                        .or(org.jooq.impl.DSL.field("rerank_profile_id").eq(id))));
    }

    @Override
    public List<ModelProfile> findAll(UUID organizationId) {
        return dsl.fetch("""
                SELECT id, organization_id, profile_type, provider, name, model_name, base_url,
                       encrypted_api_key, settings::text AS settings, enabled, test_status,
                       last_tested_at, last_test_message, capabilities::text AS capabilities,
                       created_at, updated_at
                FROM model_profile
                WHERE organization_id = ?
                ORDER BY profile_type, name
                """, organizationId).map(this::map);
    }

    private ModelProfile map(Record record) {
        return new ModelProfile(
                record.get("id", UUID.class),
                record.get("organization_id", UUID.class),
                ModelProfileType.valueOf(record.get("profile_type", String.class)),
                ModelProvider.valueOf(record.get("provider", String.class)),
                record.get("name", String.class),
                record.get("model_name", String.class),
                record.get("base_url", String.class),
                decryptedEnvelope(record.get("encrypted_api_key", byte[].class)),
                mapJson(record.get("settings", String.class)),
                Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
                ModelProfileTestStatus.valueOf(record.get("test_status", String.class)),
                instant(record.get("last_tested_at", OffsetDateTime.class)),
                record.get("last_test_message", String.class),
                mapJson(record.get("capabilities", String.class)),
                record.get("created_at", OffsetDateTime.class).toInstant(),
                record.get("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private byte[] encryptedBytes(String envelope) {
        return envelope == null ? null : envelope.getBytes(StandardCharsets.UTF_8);
    }

    private String decryptedEnvelope(byte[] encrypted) {
        return encrypted == null ? null : new String(encrypted, StandardCharsets.UTF_8);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize model profile data", exception);
        }
    }

    private Map<String, Object> mapJson(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid model profile JSON", exception);
        }
    }

    private OffsetDateTime offset(java.time.Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
