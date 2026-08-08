package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.model.EmbeddingModelReference;
import java.util.List;
import java.time.Duration;

public interface EmbeddingModelPort {
    List<List<Float>> embed(EmbeddingModelReference model, List<String> texts);

    default List<List<Float>> embed(EmbeddingModelReference model, List<String> texts, Duration timeout) {
        return embed(model, texts);
    }
}
