package com.yanyue.rag.contract.chat;

import java.util.List;

public record ConversationPage(List<ConversationView> items, String nextCursor) {
    public ConversationPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
