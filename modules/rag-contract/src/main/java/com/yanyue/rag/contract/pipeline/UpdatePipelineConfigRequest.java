package com.yanyue.rag.contract.pipeline;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record UpdatePipelineConfigRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull UUID chatProfileId,
        @NotNull UUID queryRewriteProfileId,
        @NotNull UUID rerankProfileId,
        @Min(1) @Max(100) int keywordTopK,
        @Min(1) @Max(100) int semanticTopK,
        @Min(1) @Max(100) int rrfCandidateLimit,
        @Min(1) @Max(100) int rerankCandidateLimit,
        @Min(1) @Max(20) int finalContextGroups,
        @Min(500) @Max(32_000) int contextTokenBudget,
        @DecimalMin("0.0") @DecimalMax("1.0") double minimumRerankScore,
        @Min(5) @Max(300) int fastTimeoutSeconds,
        @Min(1) @Max(100) Integer maxIterations,
        @Min(1) @Max(20) Integer maxRetrievalRounds,
        @Min(1) @Max(32) Integer maxSubQueries,
        @Min(1) @Max(100) Integer maxSearchCalls,
        @Min(1) @Max(100) Integer maxDeepReadCalls,
        @Min(1) @Max(20) Integer maxToolCallsPerRound,
        @Min(1) @Max(64) Integer maxFinalReferences,
        @Min(1) @Max(20) Integer recentTurns,
        @Min(2_000) @Max(1_000_000) Integer maxContextTokens,
        @Min(5) @Max(600) Integer llmTimeoutSeconds,
        @Min(30) @Max(1_800) Integer agenticLoopTimeoutSeconds,
        @Min(5) @Max(300) Integer toolTimeoutSeconds,
        @Min(1) @Max(16_384) Integer maxCompletionTokens,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        Boolean parallelToolCalls,
        Boolean requireDeepReadBeforeAnswer
) {
}
