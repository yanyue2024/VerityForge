package com.yanyue.rag.domain.port;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuestionSuggestionCachePort {
    Optional<CachedBatch> find(String fingerprint);

    void save(String fingerprint, CachedBatch batch, Duration ttl);

    record CachedBatch(UUID batchId, List<CachedQuestion> questions) {
        public CachedBatch {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    record CachedQuestion(UUID id, String text) {
    }
}
