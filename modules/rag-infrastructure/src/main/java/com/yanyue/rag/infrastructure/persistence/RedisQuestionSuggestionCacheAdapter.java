package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.port.QuestionSuggestionCachePort;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisQuestionSuggestionCacheAdapter implements QuestionSuggestionCachePort {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisQuestionSuggestionCacheAdapter.class);
    private static final String KEY_PREFIX = "rag:question-suggestions:v1:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisQuestionSuggestionCacheAdapter(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CachedBatch> find(String fingerprint) {
        try {
            var value = redis.opsForValue().get(key(fingerprint));
            return value == null || value.isBlank()
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(value, CachedBatch.class));
        } catch (JsonProcessingException | RuntimeException exception) {
            LOGGER.debug("Question suggestion cache read failed: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void save(String fingerprint, CachedBatch batch, Duration ttl) {
        try {
            redis.opsForValue().set(key(fingerprint), objectMapper.writeValueAsString(batch), ttl);
        } catch (JsonProcessingException | RuntimeException exception) {
            LOGGER.debug("Question suggestion cache write failed: {}", exception.getClass().getSimpleName());
        }
    }

    private String key(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid question suggestion fingerprint");
        }
        return KEY_PREFIX + fingerprint;
    }
}
