package com.yanyue.rag.worker.ingestion;

import com.yanyue.rag.application.telemetry.RagTelemetry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.data.domain.Range;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IngestionStreamConsumer {
    private final StringRedisTemplate redis;
    private final IngestionJobProcessor processor;
    private final String stream;
    private final String group;
    private final String consumerName;
    private final String deadLetterStream;
    private final Duration claimIdle;
    private final int claimBatchSize;
    private final RagTelemetry telemetry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public IngestionStreamConsumer(
            StringRedisTemplate redis,
            IngestionJobProcessor processor,
            @Value("${rag.ingestion.stream}") String stream,
            @Value("${rag.ingestion.consumer-group}") String group,
            @Value("${rag.ingestion.consumer-name}") String consumerName,
            @Value("${rag.ingestion.claim-idle-ms:30000}") long claimIdleMs,
            @Value("${rag.ingestion.claim-batch-size:4}") int claimBatchSize,
            RagTelemetry telemetry
    ) {
        this.redis = redis;
        this.processor = processor;
        this.stream = stream;
        this.group = group;
        this.consumerName = consumerName;
        this.deadLetterStream = stream + ":dead-letter";
        this.claimIdle = Duration.ofMillis(Math.max(1, claimIdleMs));
        this.claimBatchSize = Math.max(1, claimBatchSize);
        this.telemetry = telemetry;
    }

    @PostConstruct
    synchronized void createGroup() {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
        } catch (RedisSystemException exception) {
            if (!isBusyGroup(exception)) {
                redis.opsForStream().add(stream, java.util.Map.of("bootstrap", "true"));
                try {
                    redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
                } catch (RedisSystemException retry) {
                    if (!isBusyGroup(retry)) throw retry;
                }
            }
        } finally {
            redis.persist(stream);
        }
    }

    @PreDestroy
    void close() {
        executor.close();
    }

    @Scheduled(fixedDelayString = "${rag.ingestion.poll-delay-ms:500}")
    public void poll() {
        try {
            var records = read(ReadOffset.from("0"));
            if (records == null || records.isEmpty()) records = reclaimStale();
            if (records == null || records.isEmpty()) records = read(ReadOffset.lastConsumed());
            dispatch(records);
        } catch (RedisSystemException exception) {
            if (!isNoGroup(exception)) throw exception;
            telemetry.increment("rag.ingestion.stream.group_recovered", Map.of("group", group));
            createGroup();
        }
    }

    private void dispatch(List<MapRecord<String, Object, Object>> records) {
        if (records == null) return;
        records.forEach(record -> {
            var recordId = record.getId().getValue();
            if (inFlight.add(recordId)) {
                executor.submit(() -> process(record));
            }
        });
    }

    private List<MapRecord<String, Object, Object>> reclaimStale() {
        var pending = redis.opsForStream().pending(stream, group, Range.unbounded(), claimBatchSize, claimIdle);
        if (pending == null || pending.isEmpty()) return List.of();
        var ids = pending.stream()
                .filter(message -> !consumerName.equals(message.getConsumerName()))
                .map(org.springframework.data.redis.connection.stream.PendingMessage::getId)
                .toArray(org.springframework.data.redis.connection.stream.RecordId[]::new);
        if (ids.length == 0) return List.of();
        var claimed = redis.opsForStream().claim(stream, group, consumerName, claimIdle, ids);
        telemetry.increment("rag.ingestion.stream.reclaimed", Map.of("group", group), claimed.size());
        return claimed;
    }

    private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
        return redis.opsForStream().read(
                Consumer.from(group, consumerName),
                StreamReadOptions.empty().count(4).block(Duration.ofMillis(250)),
                StreamOffset.create(stream, offset)
        );
    }

    private void process(MapRecord<String, Object, Object> record) {
        try {
            var raw = record.getValue().get("jobId");
            if (raw == null) {
                if ("true".equals(String.valueOf(record.getValue().get("bootstrap")))) {
                    acknowledge(record);
                } else {
                    deadLetter(record, "MISSING_JOB_ID", "");
                }
                return;
            }
            final UUID jobId;
            try {
                jobId = UUID.fromString(raw.toString());
            } catch (IllegalArgumentException exception) {
                deadLetter(record, "INVALID_JOB_ID", raw.toString());
                return;
            }
            try {
                processor.process(jobId);
                acknowledge(record);
            } catch (RuntimeException exception) {
                telemetry.increment("rag.ingestion.stream.processing_error", Map.of(
                        "group", group,
                        "error", exception.getClass().getSimpleName()
                ));
            }
        } finally {
            inFlight.remove(record.getId().getValue());
        }
    }

    private void deadLetter(MapRecord<String, Object, Object> record, String reason, String rawJobId) {
        redis.opsForStream().add(deadLetterStream, Map.of(
                "sourceStream", stream,
                "sourceRecordId", record.getId().getValue(),
                "reason", reason,
                "jobId", rawJobId
        ));
        acknowledge(record);
        telemetry.increment("rag.ingestion.stream.dead_letter", Map.of("reason", reason));
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redis.opsForStream().acknowledge(stream, group, record.getId());
    }

    static boolean isBusyGroup(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            var message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) return true;
        }
        return false;
    }

    static boolean isNoGroup(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            var message = current.getMessage();
            if (message != null && message.contains("NOGROUP")) return true;
        }
        return false;
    }
}
