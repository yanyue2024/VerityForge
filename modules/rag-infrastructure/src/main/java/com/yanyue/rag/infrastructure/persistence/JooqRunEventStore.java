package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.StreamEvent;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.port.RunEventPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JooqRunEventStore implements RunEventPort {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JooqRunEventStore(DSLContext dsl, ObjectMapper objectMapper, Clock clock) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public StreamEvent append(UUID runId, StreamEventType type, Object payload) {
        var sequenceRecord = dsl.fetchOne("""
                INSERT INTO rag_run_sequence (run_id, next_sequence)
                VALUES (?, 2)
                ON CONFLICT (run_id) DO UPDATE
                    SET next_sequence = rag_run_sequence.next_sequence + 1
                RETURNING next_sequence - 1
                """, runId);
        long sequence = ((Number) sequenceRecord.get(0)).longValue();
        var event = new StreamEvent(UUID.randomUUID(), runId, sequence, type, clock.instant(), payload);
        dsl.execute("""
                INSERT INTO rag_run_event (event_id, run_id, sequence, event_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::timestamptz)
                """, event.eventId(), event.runId(), event.sequence(), event.type().name(), json(payload),
                OffsetDateTime.ofInstant(event.timestamp(), ZoneOffset.UTC));
        return event;
    }

    @Override
    public List<StreamEvent> replay(UUID runId, long afterSequence) {
        return dsl.fetch("""
                SELECT event_id, run_id, sequence, event_type, payload::text AS payload, created_at
                FROM rag_run_event
                WHERE run_id = ? AND sequence > ?
                ORDER BY sequence
                """, runId, afterSequence).map(this::map);
    }

    private StreamEvent map(Record record) {
        try {
            return new StreamEvent(
                    record.get("event_id", UUID.class),
                    record.get("run_id", UUID.class),
                    record.get("sequence", Long.class),
                    StreamEventType.valueOf(record.get("event_type", String.class)),
                    record.get("created_at", OffsetDateTime.class).toInstant(),
                    objectMapper.readValue(record.get("payload", String.class), Object.class)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid run event payload", exception);
        }
    }

    private String json(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize run event", exception);
        }
    }
}
