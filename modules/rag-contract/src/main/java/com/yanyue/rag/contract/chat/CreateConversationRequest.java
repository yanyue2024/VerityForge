package com.yanyue.rag.contract.chat;

import jakarta.validation.constraints.Size;

public record CreateConversationRequest(@Size(max = 200) String title, ConversationSettings settings) {
}
