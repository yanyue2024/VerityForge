package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.UUID;

public interface QueryRewriteModelPort {
    RewriteResult rewrite(UUID profileId, String query, List<String> recentMessages);

    record RewriteResult(
            boolean rewriteNeeded,
            String standaloneQuery,
            List<String> resolvedReferences,
            String fallbackReason
    ) {
        public RewriteResult {
            resolvedReferences = resolvedReferences == null ? List.of() : List.copyOf(resolvedReferences);
        }

        public static RewriteResult unchanged(String query, String reason) {
            return new RewriteResult(false, query, List.of(), reason);
        }
    }
}
