package com.yanyue.rag.domain.port;

import java.util.UUID;

public interface CitationPort {
    void save(UUID runId, int citationIndex, RetrievalHit hit);
}
