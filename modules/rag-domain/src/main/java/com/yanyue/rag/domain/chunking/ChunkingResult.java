package com.yanyue.rag.domain.chunking;

import com.yanyue.rag.domain.chunking.ChunkSourceMap;
import com.yanyue.rag.domain.knowledge.Chunk;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChunkingResult(List<Chunk> chunks, Map<UUID, ChunkSourceMap> sourceMaps) {
    public ChunkingResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        sourceMaps = sourceMaps == null ? Map.of() : Map.copyOf(sourceMaps);
    }
}
