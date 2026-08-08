package com.yanyue.rag.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PipelineConfig(
        UUID id,
        UUID organizationId,
        String name,
        String pipelineVersion,
        String promptVersion,
        UUID chatProfileId,
        UUID queryRewriteProfileId,
        UUID rerankProfileId,
        int keywordTopK,
        int semanticTopK,
        int rrfCandidateLimit,
        int rerankCandidateLimit,
        int finalContextGroups,
        int contextTokenBudget,
        double minimumRerankScore,
        int fastTimeoutSeconds,
        int maxIterations,
        int maxRetrievalRounds,
        int maxSubQueries,
        int maxSearchCalls,
        int maxDeepReadCalls,
        int maxToolCallsPerRound,
        int maxFinalReferences,
        int recentTurns,
        int maxContextTokens,
        int llmTimeoutSeconds,
        int agenticLoopTimeoutSeconds,
        int toolTimeoutSeconds,
        int maxCompletionTokens,
        double temperature,
        boolean parallelToolCalls,
        boolean requireDeepReadBeforeAnswer,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public PipelineConfig(
            UUID id, UUID organizationId, String name, String pipelineVersion, String promptVersion,
            UUID chatProfileId, UUID queryRewriteProfileId, UUID rerankProfileId,
            int keywordTopK, int semanticTopK, int rrfCandidateLimit, int rerankCandidateLimit,
            int finalContextGroups, int contextTokenBudget, double minimumRerankScore, int fastTimeoutSeconds,
            boolean active, Instant createdAt, Instant updatedAt
    ) {
        this(id, organizationId, name, pipelineVersion, promptVersion, chatProfileId, queryRewriteProfileId,
                rerankProfileId, keywordTopK, semanticTopK, rrfCandidateLimit, rerankCandidateLimit,
                finalContextGroups, contextTokenBudget, minimumRerankScore, fastTimeoutSeconds,
                35, 5, 8, 16, 20, 6, 16, 5, 200_000, 120, 300, 60, 2_048, 0.7, false, true,
                active, createdAt, updatedAt);
    }
}
