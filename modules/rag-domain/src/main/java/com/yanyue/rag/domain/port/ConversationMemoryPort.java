package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.UUID;

public interface ConversationMemoryPort {
    List<String> recentMessages(UUID conversationId, int turns);
    void append(UUID conversationId, String role, String content, UUID runId);
}
