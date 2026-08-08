package com.yanyue.rag.infrastructure.persistence;

import com.yanyue.rag.domain.port.ConversationMemoryPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import java.time.Duration;

@Repository
public class JooqConversationMemory implements ConversationMemoryPort {
    private final DSLContext dsl;
    private final StringRedisTemplate redis;
    private static final int MAX_RECENT_MESSAGES = 16;
    private static final Duration TTL = Duration.ofDays(7);

    public JooqConversationMemory(DSLContext dsl, StringRedisTemplate redis) {
        this.dsl = dsl;
        this.redis = redis;
    }

    @Override
    public List<String> recentMessages(UUID conversationId, int turns) {
        var limit = Math.max(1, turns * 2);
        // Turn replacement changes which assistant answer is visible. Always
        // read the authoritative rows so a reprocessed answer never inherits
        // the superseded assistant response from Redis.
        var loaded = loadFromDatabase(conversationId, limit);
        warmCache(conversationId, loaded);
        return loaded;
    }

    private List<String> loadFromDatabase(UUID conversationId, int limit) {
        var values = new ArrayList<>(dsl.fetch("""
                SELECT message.role, message.content
                FROM conversation_message message
                LEFT JOIN conversation_turn turn ON turn.id = message.turn_id
                LEFT JOIN rag_run active_run ON active_run.id = turn.active_run_id
                WHERE message.conversation_id = ?
                  AND (
                    message.turn_id IS NULL
                    OR message.role NOT IN ('user', 'assistant')
                    OR (message.role = 'assistant' AND message.run_id = turn.active_run_id)
                    OR (message.role = 'user' AND active_run.status NOT IN ('QUEUED', 'RUNNING'))
                  )
                ORDER BY message.created_at DESC LIMIT ?
                """, conversationId, limit).map(record ->
                record.get("role", String.class) + ": " + record.get("content", String.class)));
        java.util.Collections.reverse(values);
        return List.copyOf(values);
    }

    @Override
    public void append(UUID conversationId, String role, String content, UUID runId) {
        var turnId = dsl.fetchValue("SELECT turn_id FROM rag_run WHERE id = ?", runId, UUID.class);
        if ("user".equals(role) && turnId != null) {
            dsl.execute("""
                    INSERT INTO conversation_message (conversation_id, role, content, metadata, run_id, turn_id)
                    VALUES (?, 'user', ?, jsonb_build_object('runId', ?::text), ?, ?)
                    ON CONFLICT (turn_id, role)
                        WHERE turn_id IS NOT NULL AND role = 'user'
                    DO UPDATE SET content = EXCLUDED.content
                    """, conversationId, content, runId, runId, turnId);
        } else {
            dsl.execute("""
                    INSERT INTO conversation_message (conversation_id, role, content, metadata, run_id, turn_id)
                    VALUES (?, ?, ?, jsonb_build_object('runId', ?::text), ?, ?)
                    ON CONFLICT (run_id, role)
                        WHERE run_id IS NOT NULL AND role IN ('user', 'assistant')
                    DO UPDATE SET
                        content = EXCLUDED.content,
                        metadata = EXCLUDED.metadata,
                        turn_id = EXCLUDED.turn_id
                    """, conversationId, role, content, runId, runId, turnId);
        }
        try {
            redis.delete(recentKey(conversationId));
        } catch (RuntimeException ignored) {
            // A Redis outage must not lose the message already committed to PostgreSQL.
        }
    }

    private void warmCache(UUID conversationId, List<String> messages) {
        if (messages.isEmpty()) return;
        try {
            var key = recentKey(conversationId);
            redis.delete(key);
            redis.opsForList().rightPushAll(key, messages.stream().map(value -> compact(value, 4000)).toList());
            redis.expire(key, TTL);
        } catch (RuntimeException ignored) {
            // Cache warmup is best effort.
        }
    }

    private void appendSummary(UUID conversationId, String message) {
        var key = summaryKey(conversationId);
        var current = redis.opsForValue().get(key);
        var next = (current == null || current.isBlank() ? "" : current + " | ") + compact(message, 300);
        redis.opsForValue().set(key, compact(next, 2000), TTL);
    }

    private String recentKey(UUID conversationId) {
        return "rag:conversation:" + conversationId + ":recent";
    }

    private String summaryKey(UUID conversationId) {
        return "rag:conversation:" + conversationId + ":summary";
    }

    private String compact(String value, int maximum) {
        var normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
