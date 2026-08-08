package com.yanyue.rag.domain.port;

import com.yanyue.rag.contract.chat.RunMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuestionSuggestionCatalogPort {
    Optional<Catalog> find(UUID organizationId, UUID userId, RunMode mode, UUID knowledgeBaseId);

    void save(
            UUID organizationId,
            UUID userId,
            RunMode mode,
            UUID knowledgeBaseId,
            Catalog catalog,
            Duration ttl
    );

    record Catalog(
            String contentRevision,
            UUID pipelineConfigId,
            Instant generatedAt,
            List<CatalogQuestion> questions
    ) {
        public Catalog {
            contentRevision = contentRevision == null ? "" : contentRevision;
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    record CatalogQuestion(
            UUID id,
            String text,
            String kind,
            double quality,
            List<SupportEvidence> evidence
    ) {
        public CatalogQuestion {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record SupportEvidence(UUID chunkId, UUID documentVersionId) {
    }
}
