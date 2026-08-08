package com.yanyue.rag.domain.agent.react;

import com.yanyue.rag.contract.chat.CreateRunRequest;
import java.util.UUID;

public record ReactRecoverableRun(
        UUID runId,
        UUID organizationId,
        UUID userId,
        UUID conversationId,
        CreateRunRequest request
) {
}
