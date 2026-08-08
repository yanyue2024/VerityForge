package com.yanyue.rag.domain.port;

import com.yanyue.rag.contract.chat.StreamEvent;
import com.yanyue.rag.contract.chat.StreamEventType;
import java.util.List;
import java.util.UUID;

public interface RunEventPort {
    StreamEvent append(UUID runId, StreamEventType type, Object payload);
    List<StreamEvent> replay(UUID runId, long afterSequence);
}
