package com.yanyue.rag.contract.chat;

import java.time.Instant;
import java.util.UUID;

public record ConversationView(
        UUID id,
        String title,
        ConversationSettings settings,
        boolean pinned,
        Instant pinnedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
