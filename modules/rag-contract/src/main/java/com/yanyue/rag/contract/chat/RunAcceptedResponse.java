package com.yanyue.rag.contract.chat;

import java.util.UUID;

public record RunAcceptedResponse(UUID runId, RunMode requestedMode, String eventsUrl) {
}
