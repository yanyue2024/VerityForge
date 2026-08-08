package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.port.CredentialRotationRepository;
import com.yanyue.rag.domain.security.CredentialLocation;
import com.yanyue.rag.domain.security.CredentialRotationAudit;
import com.yanyue.rag.domain.security.StoredCredential;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqCredentialRotationRepository implements CredentialRotationRepository {
    private static final TypeReference<Map<String, Integer>> COUNT_MAP = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public JooqCredentialRotationRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public void lockCredentialStores() {
        dsl.execute("""
                LOCK TABLE model_profile, evaluation_schedule, evaluation_notification_delivery
                IN SHARE ROW EXCLUSIVE MODE
                """);
    }

    @Override
    public List<StoredCredential> findAllCredentials() {
        var result = new ArrayList<StoredCredential>();
        dsl.fetch("SELECT id, encrypted_api_key FROM model_profile WHERE encrypted_api_key IS NOT NULL")
                .forEach(record -> result.add(new StoredCredential(
                        CredentialLocation.MODEL_PROFILE,
                        record.get("id", UUID.class),
                        new String(record.get("encrypted_api_key", byte[].class), StandardCharsets.UTF_8))));
        dsl.fetch("""
                SELECT id, webhook_secret_ciphertext
                FROM evaluation_schedule
                WHERE webhook_secret_ciphertext IS NOT NULL
                """).forEach(record -> result.add(new StoredCredential(
                        CredentialLocation.EVALUATION_SCHEDULE,
                        record.get("id", UUID.class),
                        record.get("webhook_secret_ciphertext", String.class))));
        dsl.fetch("""
                SELECT id, webhook_secret_ciphertext
                FROM evaluation_notification_delivery
                WHERE webhook_secret_ciphertext IS NOT NULL
                """).forEach(record -> result.add(new StoredCredential(
                        CredentialLocation.EVALUATION_DELIVERY,
                        record.get("id", UUID.class),
                        record.get("webhook_secret_ciphertext", String.class))));
        return List.copyOf(result);
    }

    @Override
    public void updateCredential(StoredCredential credential, String ciphertext) {
        var updated = switch (credential.location()) {
            case MODEL_PROFILE -> dsl.execute(
                    "UPDATE model_profile SET encrypted_api_key = ?, updated_at = now() WHERE id = ?",
                    ciphertext.getBytes(StandardCharsets.UTF_8), credential.id());
            case EVALUATION_SCHEDULE -> dsl.execute("""
                    UPDATE evaluation_schedule
                    SET webhook_secret_ciphertext = ?, updated_at = now()
                    WHERE id = ?
                    """, ciphertext, credential.id());
            case EVALUATION_DELIVERY -> dsl.execute("""
                    UPDATE evaluation_notification_delivery
                    SET webhook_secret_ciphertext = ?, updated_at = now()
                    WHERE id = ?
                    """, ciphertext, credential.id());
        };
        if (updated != 1) throw new IllegalStateException("Credential disappeared during rotation");
    }

    @Override
    public void saveAudit(CredentialRotationAudit audit) {
        dsl.execute("""
                INSERT INTO credential_rotation_audit
                    (id, active_key_id, rotated_by, total_credentials, rotated_credentials,
                     source_counts, previous_key_counts, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::timestamptz)
                """, audit.id(), audit.activeKeyId(), audit.rotatedBy(), audit.totalCredentials(),
                audit.rotatedCredentials(), json(audit.sourceCounts()), json(audit.previousKeyCounts()),
                OffsetDateTime.ofInstant(audit.createdAt(), java.time.ZoneOffset.UTC));
    }

    @Override
    public Optional<CredentialRotationAudit> findLatestAudit() {
        return dsl.fetchOptional("""
                SELECT id, active_key_id, rotated_by, total_credentials, rotated_credentials,
                       source_counts::text AS source_counts,
                       previous_key_counts::text AS previous_key_counts, created_at
                FROM credential_rotation_audit
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """).map(this::audit);
    }

    private CredentialRotationAudit audit(Record record) {
        return new CredentialRotationAudit(
                record.get("id", UUID.class), record.get("active_key_id", String.class),
                record.get("rotated_by", UUID.class), record.get("total_credentials", Integer.class),
                record.get("rotated_credentials", Integer.class), map(record.get("source_counts", String.class)),
                map(record.get("previous_key_counts", String.class)),
                record.get("created_at", OffsetDateTime.class).toInstant());
    }

    private String json(Map<String, Integer> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize credential rotation audit", exception);
        }
    }

    private Map<String, Integer> map(String value) {
        try {
            return objectMapper.readValue(value, COUNT_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid credential rotation audit JSON", exception);
        }
    }
}
