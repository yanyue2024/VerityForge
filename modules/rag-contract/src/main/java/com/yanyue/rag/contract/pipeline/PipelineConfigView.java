package com.yanyue.rag.contract.pipeline;

import java.time.Instant;
import java.util.UUID;

public record PipelineConfigView(
        UUID id,
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
}
