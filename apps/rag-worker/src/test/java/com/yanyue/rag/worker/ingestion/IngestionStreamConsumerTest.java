package com.yanyue.rag.worker.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;

class IngestionStreamConsumerTest {
    @Test
    void detectsBusyGroupInWrappedRedisCause() {
        var failure = new RedisSystemException(
                "Error in execution",
                new IllegalStateException("BUSYGROUP Consumer Group name already exists")
        );

        assertThat(IngestionStreamConsumer.isBusyGroup(failure)).isTrue();
        assertThat(IngestionStreamConsumer.isBusyGroup(
                new RedisSystemException("Connection refused", null)
        )).isFalse();
    }

    @Test
    void detectsMissingGroupInWrappedRedisCause() {
        var failure = new RedisSystemException(
                "Read failed",
                new IllegalStateException("NOGROUP No such key or consumer group")
        );

        assertThat(IngestionStreamConsumer.isNoGroup(failure)).isTrue();
        assertThat(IngestionStreamConsumer.isNoGroup(
                new RedisSystemException("Connection refused", null)
        )).isFalse();
    }
}
