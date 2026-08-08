package com.yanyue.rag.infrastructure.persistence;

import com.yanyue.rag.contract.memory.MemoryConfirmationStatus;
import com.yanyue.rag.domain.model.MemoryFact;
import com.yanyue.rag.domain.port.MemoryFactRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class JooqMemoryFactRepository implements MemoryFactRepository {
    private final DSLContext dsl;

    public JooqMemoryFactRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public MemoryFact save(MemoryFact fact) {
        dsl.execute("""
                INSERT INTO memory_fact
                    (id, organization_id, user_id, fact_text, source_message_id, confidence,
                     confirmation_status, valid_from, valid_to, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz,
                        ?::timestamptz, ?::timestamptz)
                ON CONFLICT (id) DO UPDATE
                SET fact_text = EXCLUDED.fact_text,
                    confidence = EXCLUDED.confidence,
                    confirmation_status = EXCLUDED.confirmation_status,
                    valid_from = EXCLUDED.valid_from,
                    valid_to = EXCLUDED.valid_to,
                    updated_at = EXCLUDED.updated_at
                """, fact.id(), fact.organizationId(), fact.userId(), fact.factText(), fact.sourceMessageId(),
                fact.confidence(), fact.status().name(), offset(fact.validFrom()), offset(fact.validTo()),
                offset(fact.createdAt()), offset(fact.updatedAt()));
        return find(fact.organizationId(), fact.userId(), fact.id())
                .orElseThrow(() -> new IllegalStateException("Saved Memory Fact could not be loaded"));
    }

    @Override
    public Optional<MemoryFact> find(UUID organizationId, UUID userId, UUID factId) {
        return dsl.fetchOptional("""
                SELECT * FROM memory_fact WHERE id = ? AND organization_id = ? AND user_id = ?
                """, factId, organizationId, userId).map(this::map);
    }

    @Override
    public List<MemoryFact> findAll(UUID organizationId, UUID userId) {
        return dsl.fetch("""
                SELECT * FROM memory_fact
                WHERE organization_id = ? AND user_id = ?
                ORDER BY confirmation_status, updated_at DESC
                """, organizationId, userId).map(this::map);
    }

    @Override
    public List<MemoryFact> findConfirmedActive(UUID organizationId, UUID userId, Instant at, int limit) {
        return dsl.fetch("""
                SELECT * FROM memory_fact
                WHERE organization_id = ? AND user_id = ?
                  AND confirmation_status = 'CONFIRMED'
                  AND (valid_from IS NULL OR valid_from <= ?::timestamptz)
                  AND (valid_to IS NULL OR valid_to > ?::timestamptz)
                ORDER BY updated_at DESC
                LIMIT ?
                """, organizationId, userId, offset(at), offset(at), Math.max(1, limit)).map(this::map);
    }

    @Override
    public boolean sourceMessageBelongsTo(UUID organizationId, UUID userId, UUID sourceMessageId) {
        return dsl.fetchExists(dsl.selectOne()
                .from("conversation_message AS m")
                .join("conversation AS c").on("c.id = m.conversation_id")
                .where(org.jooq.impl.DSL.field("m.id").eq(sourceMessageId))
                .and(org.jooq.impl.DSL.field("c.organization_id").eq(organizationId))
                .and(org.jooq.impl.DSL.field("c.created_by").eq(userId)));
    }

    @Override
    public boolean delete(UUID organizationId, UUID userId, UUID factId) {
        return dsl.execute("DELETE FROM memory_fact WHERE id = ? AND organization_id = ? AND user_id = ?",
                factId, organizationId, userId) > 0;
    }

    private MemoryFact map(Record record) {
        return new MemoryFact(
                record.get("id", UUID.class), record.get("organization_id", UUID.class),
                record.get("user_id", UUID.class), record.get("fact_text", String.class),
                record.get("source_message_id", UUID.class),
                record.get("confidence", java.math.BigDecimal.class).doubleValue(),
                MemoryConfirmationStatus.valueOf(record.get("confirmation_status", String.class)),
                instant(record.get("valid_from", OffsetDateTime.class)),
                instant(record.get("valid_to", OffsetDateTime.class)),
                record.get("created_at", OffsetDateTime.class).toInstant(),
                record.get("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private OffsetDateTime offset(java.time.Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
