package com.yanyue.rag.worker.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.application.telemetry.RagTelemetry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
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
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IngestionTransportIntegrationTest {
    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static DSLContext dsl;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");
        redisContainer = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        postgres.start();
        redisContainer.start();

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);

        redisFactory = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (redisFactory != null) redisFactory.destroy();
        if (redisContainer != null) redisContainer.stop();
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearState() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        dsl.execute("TRUNCATE TABLE outbox_event");
    }

    @Test
    void newConsumerClaimsAndAcknowledgesAnotherConsumersIdlePendingRecord() {
        var stream = "test:ingestion:handoff";
        var group = "workers";
        var jobId = UUID.randomUUID();
        redis.opsForStream().add(stream, Map.of("bootstrap", "true"));
        redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
        redis.opsForStream().add(stream, Map.of("jobId", jobId.toString()));
        var delivered = redis.opsForStream().read(
                Consumer.from(group, "worker-old"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        assertThat(delivered).hasSize(1);
        sleep(25);

        var processor = mock(IngestionJobProcessor.class);
        var consumer = consumer(redis, processor, stream, group, "worker-new", 5);
        try {
            consumer.createGroup();
            consumer.poll();
            verify(processor, timeout(2_000)).process(jobId);
            await(() -> redis.opsForStream().pending(stream, group).getTotalPendingMessages() == 0);
            assertThat(redis.opsForStream().pending(stream, group).getTotalPendingMessages()).isZero();
        } finally {
            consumer.close();
        }
    }

    @Test
    void startupRemovesLegacyTtlAndPollingRecreatesADeletedGroup() {
        var stream = "test:ingestion:group-recovery";
        var group = "workers";
        redis.opsForStream().add(stream, Map.of("bootstrap", "true"));
        redis.expire(stream, Duration.ofDays(14));
        var consumer = consumer(redis, mock(IngestionJobProcessor.class), stream, group, "worker-new", 5);
        try {
            consumer.createGroup();
            assertThat(redis.getExpire(stream)).isEqualTo(-1);

            redis.opsForStream().destroyGroup(stream, group);
            consumer.poll();

            assertThat(redis.opsForStream().groups(stream).stream().map(value -> value.groupName()))
                    .contains(group);
        } finally {
            consumer.close();
        }
    }

    @Test
    void malformedJobIdMovesToDeadLetterAndDoesNotBlockThePendingList() {
        var stream = "test:ingestion:dead-letter";
        var group = "workers";
        redis.opsForStream().add(stream, Map.of("bootstrap", "true"));
        redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
        redis.opsForStream().add(stream, Map.of("jobId", "not-a-uuid"));
        var consumer = consumer(redis, mock(IngestionJobProcessor.class), stream, group, "worker-new", 5);
        try {
            consumer.poll();
            await(() -> redis.opsForStream().pending(stream, group).getTotalPendingMessages() == 0);
            var deadLetters = redis.opsForStream().range(stream + ":dead-letter", Range.unbounded());
            assertThat(deadLetters).hasSize(1);
            assertThat(deadLetters.getFirst().getValue())
                    .containsEntry("reason", "INVALID_JOB_ID")
                    .containsEntry("jobId", "not-a-uuid");
        } finally {
            consumer.close();
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void redisFailureCommitsOutboxAttemptAndBackoffInsteadOfRollingThemBack() {
        var eventId = insertOutboxEvent();
        var failingRedis = mock(StringRedisTemplate.class);
        StreamOperations operations = mock(StreamOperations.class);
        when(failingRedis.opsForStream()).thenReturn(operations);
        when(operations.add(any(MapRecord.class)))
                .thenThrow(new RedisConnectionFailureException("injected Redis outage"));
        var before = OffsetDateTime.now();

        new OutboxPublisher(dsl, failingRedis, transactions, "test:outbox", RagTelemetry.noop()).publishPending();

        var event = dsl.fetchOne("""
                SELECT status, attempts, available_at, published_at FROM outbox_event WHERE id = ?
                """, eventId);
        assertThat(event.get("status", String.class)).isEqualTo("PENDING");
        assertThat(event.get("attempts", Integer.class)).isEqualTo(1);
        assertThat(event.get("available_at", OffsetDateTime.class)).isAfter(before.plusSeconds(4));
        assertThat(event.get("published_at", OffsetDateTime.class)).isNull();
    }

    @Test
    void successfulOutboxPublishLeavesTheStreamPersistent() {
        var eventId = insertOutboxEvent();
        var stream = "test:outbox:persistent";
        redis.opsForStream().add(stream, Map.of("bootstrap", "true"));
        redis.expire(stream, Duration.ofDays(14));

        new OutboxPublisher(dsl, redis, transactions, stream, RagTelemetry.noop()).publishPending();

        assertThat(dsl.fetchOne("SELECT status FROM outbox_event WHERE id = ?", eventId).get(0, String.class))
                .isEqualTo("PUBLISHED");
        assertThat(redis.getExpire(stream)).isEqualTo(-1);
        assertThat(redis.opsForStream().size(stream)).isEqualTo(2);
    }

    private IngestionStreamConsumer consumer(
            StringRedisTemplate template,
            IngestionJobProcessor processor,
            String stream,
            String group,
            String name,
            long claimIdleMs
    ) {
        return new IngestionStreamConsumer(template, processor, stream, group, name,
                claimIdleMs, 4, RagTelemetry.noop());
    }

    private UUID insertOutboxEvent() {
        var eventId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'IngestionJob', ?, 'ingestion.requested', jsonb_build_object('jobId', ?::text))
                """, eventId, jobId, jobId.toString());
        return eventId;
    }

    private void await(java.util.function.BooleanSupplier condition) {
        for (int index = 0; index < 100; index++) {
            if (condition.getAsBoolean()) return;
            sleep(20);
        }
        throw new AssertionError("Condition was not satisfied before timeout");
    }

    private void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
