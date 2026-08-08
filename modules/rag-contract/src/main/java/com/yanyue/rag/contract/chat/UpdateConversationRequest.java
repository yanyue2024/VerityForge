package com.yanyue.rag.contract.chat;

import jakarta.validation.constraints.Size;

public record UpdateConversationRequest(
        @Size(max = 200) String title,
        Boolean pinned,
        ConversationSettings settings
) {
}
