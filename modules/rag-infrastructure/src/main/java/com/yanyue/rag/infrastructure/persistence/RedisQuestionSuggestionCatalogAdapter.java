package com.yanyue.rag.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.port.QuestionSuggestionCatalogPort;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisQuestionSuggestionCatalogAdapter implements QuestionSuggestionCatalogPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisQuestionSuggestionCatalogAdapter.class);
    private static final String KEY_PREFIX = "rag:question-suggestion-catalog:v2:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisQuestionSuggestionCatalogAdapter(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Catalog> find(UUID organizationId, UUID userId, RunMode mode, UUID knowledgeBaseId) {
        try {
            var value = redis.opsForValue().get(key(organizationId, userId, mode, knowledgeBaseId));
            return value == null || value.isBlank()
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(value, Catalog.class));
        } catch (JsonProcessingException | RuntimeException exception) {
            LOGGER.debug("Question suggestion catalog read failed: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void save(
            UUID organizationId,
            UUID userId,
            RunMode mode,
            UUID knowledgeBaseId,
            Catalog catalog,
            Duration ttl
    ) {
        try {
            redis.opsForValue().set(key(organizationId, userId, mode, knowledgeBaseId),
                    objectMapper.writeValueAsString(catalog), ttl);
        } catch (JsonProcessingException | RuntimeException exception) {
            LOGGER.warn("Question suggestion catalog write failed: {}", exception.getClass().getSimpleName());
        }
    }

    private String key(UUID organizationId, UUID userId, RunMode mode, UUID knowledgeBaseId) {
        if (mode != RunMode.FAST && mode != RunMode.DEEP) {
            throw new IllegalArgumentException("Catalog mode must be FAST or DEEP");
        }
        return KEY_PREFIX + organizationId + ":" + userId + ":" + mode.name() + ":"
                + (knowledgeBaseId == null ? "all" : knowledgeBaseId);
    }
}
