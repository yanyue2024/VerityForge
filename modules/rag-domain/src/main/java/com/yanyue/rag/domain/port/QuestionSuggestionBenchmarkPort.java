package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface QuestionSuggestionBenchmarkPort {
    Optional<BenchmarkPool> find(
            UUID organizationId,
            List<UUID> knowledgeBaseIds,
            Set<UUID> eligibleDocumentVersionIds
    );

    record BenchmarkPool(String revision, List<BenchmarkQuestion> questions) {
        public BenchmarkPool {
            revision = revision == null ? "" : revision;
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    record BenchmarkQuestion(
            UUID id,
            String text,
            String challengeType,
            String sourceProject,
            int position
    ) {
    }
}
