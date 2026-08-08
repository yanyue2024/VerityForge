package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.knowledge.Chunk;
import java.util.List;
import java.util.UUID;

public interface ChunkRepository {
    void replaceForVersion(UUID documentVersionId, List<Chunk> chunks);
    List<Chunk> findByVersion(UUID documentVersionId);
}
