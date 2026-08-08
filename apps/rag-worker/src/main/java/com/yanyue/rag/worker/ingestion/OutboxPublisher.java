package com.yanyue.rag.worker.ingestion;

import com.yanyue.rag.application.telemetry.RagTelemetry;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxPublisher {
    private final DSLContext dsl;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private final String stream;
    private final RagTelemetry telemetry;

    public OutboxPublisher(
            DSLContext dsl,
            StringRedisTemplate redis,
            TransactionTemplate transactions,
            @Value("${rag.ingestion.stream}") String stream,
            RagTelemetry telemetry
    ) {
        this.dsl = dsl;
        this.redis = redis;
        this.transactions = transactions;
        this.stream = stream;
        this.telemetry = telemetry;
    }

    @Scheduled(fixedDelayString = "${rag.ingestion.poll-delay-ms:500}")
    public void publishPending() {
        for (int index = 0; index < 20; index++) {
            var published = transactions.execute(status -> publishOne());
            if (!Boolean.TRUE.equals(published)) return;
        }
    }

    private boolean publishOne() {
        var event = dsl.fetchOptional("""
                SELECT id, aggregate_id
                FROM outbox_event
                WHERE status = 'PENDING' AND available_at <= now()
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """).orElse(null);
        if (event == null) return false;

        var eventId = event.get("id", UUID.class);
        var jobId = event.get("aggregate_id", UUID.class);
        try {
            MapRecord<String, String, String> record = StreamRecords
                    .newRecord()
                    .in(stream)
                    .ofMap(Map.of("eventId", eventId.toString(), "jobId", jobId.toString()));
            redis.opsForStream().add(record);
            redis.persist(stream);
            dsl.execute("""
                    UPDATE outbox_event
                    SET status = 'PUBLISHED', attempts = attempts + 1, published_at = now()
                    WHERE id = ?
                    """, eventId);
        } catch (RuntimeException exception) {
            dsl.execute("""
                    UPDATE outbox_event
                    SET attempts = attempts + 1,
                        status = CASE WHEN attempts + 1 >= 10 THEN 'FAILED' ELSE 'PENDING' END,
                        available_at = now() + interval '5 seconds'
                    WHERE id = ?
                    """, eventId);
            telemetry.increment("rag.ingestion.outbox.publish_error", Map.of(
                    "error", exception.getClass().getSimpleName()
            ));
            return false;
        }
        return true;
    }
}
