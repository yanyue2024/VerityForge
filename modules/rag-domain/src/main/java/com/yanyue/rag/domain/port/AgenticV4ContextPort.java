package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.chunking.v4.ParentContext;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.util.List;
import java.util.UUID;
import java.time.Duration;

public interface AgenticV4ContextPort {
    ParentLoadResult loadParentContexts(
            List<ChildCandidate> children,
            RetrievalScope scope,
            int maximumParents
    );

    default ParentLoadResult loadParentContexts(
            List<ChildCandidate> children,
            RetrievalScope scope,
            int maximumParents,
            Duration timeout
    ) {
        return loadParentContexts(children, scope, maximumParents);
    }

    record ChildCandidate(UUID queryId, RetrievalHit hit) {
        public ChildCandidate {
            if (queryId == null || hit == null) {
                throw new IllegalArgumentException("queryId 和 hit 不能为空");
            }
        }
    }

    record ParentLoadResult(List<ParentContext> contexts, boolean evidenceMayBeHidden, int attemptedReads) {
        public ParentLoadResult {
            contexts = contexts == null ? List.of() : List.copyOf(contexts);
            if (attemptedReads < 0) throw new IllegalArgumentException("attemptedReads 不能为负数");
        }
    }
}
