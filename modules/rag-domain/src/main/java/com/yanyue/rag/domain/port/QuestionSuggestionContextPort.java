package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface QuestionSuggestionContextPort {
    SuggestionContext load(RetrievalScope scope, int maximumDocuments, int maximumExcerpts);

    EligibilitySnapshot eligibility(RetrievalScope scope);

    record SuggestionContext(String contentRevision, List<SourceExcerpt> excerpts) {
        public SuggestionContext {
            contentRevision = contentRevision == null ? "" : contentRevision;
            excerpts = excerpts == null ? List.of() : List.copyOf(excerpts);
        }
    }

    record EligibilitySnapshot(String contentRevision, Set<UUID> documentVersionIds) {
        public EligibilitySnapshot {
            contentRevision = contentRevision == null ? "" : contentRevision;
            documentVersionIds = documentVersionIds == null ? Set.of() : Set.copyOf(documentVersionIds);
        }
    }

    record SourceExcerpt(
            UUID knowledgeBaseId,
            String knowledgeBaseName,
            UUID documentId,
            UUID documentVersionId,
            String documentTitle,
            String text
    ) {
    }
}
