package com.yanyue.rag.infrastructure.agent.v4.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentV4PersistenceRecords {
    private AgentV4PersistenceRecords() {
    }

    public enum ResearchPhase {
        PRIMARY,
        REPAIR
    }

    public enum ActionStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public enum ReservationStatus {
        RESERVED,
        DISPATCHED,
        SUCCEEDED,
        FAILED,
        RELEASED
    }

    public record ChunkSourceSegment(
            int segmentOrder,
            int chunkLocalStart,
            int chunkLocalEnd,
            UUID documentBlockId,
            int blockLocalStart,
            int blockLocalEnd,
            Integer documentSourceStart,
            Integer documentSourceEnd,
            String documentOffsetUnit
    ) {
    }

    public record GoalResearchOutcome(
            UUID id,
            UUID runId,
            UUID goalId,
            ResearchPhase phase,
            String status,
            List<UUID> searchTaskIds,
            UUID deepReadLogicalCallId,
            List<UUID> acceptedEvidenceIds,
            String outcomeCategory,
            boolean mayHaveHiddenEvidence,
            Instant completedAt
    ) {
    }

    public record Evidence(
            UUID id,
            UUID runId,
            UUID goalId,
            UUID documentId,
            UUID documentVersionId,
            UUID parentChunkId,
            String spanId,
            String quote,
            Integer sourceStart,
            Integer sourceEnd,
            double retrievalScore,
            ResearchPhase firstAcceptedPhase,
            Map<String, Object> sourceAnchor,
            List<String> retrievalSources
    ) {
    }

    public record EvidenceRequirement(
            UUID requirementId,
            ResearchPhase acceptedPhase,
            UUID repairTargetId,
            String targetEffect
    ) {
    }

    public record RetrievalTask(
            UUID id,
            UUID runId,
            UUID goalId,
            ResearchPhase phase,
            String queryRole,
            String queryText,
            String searchMode,
            List<UUID> targetRequirementIds
    ) {
    }

    public record RetrievalCandidate(
            UUID retrievalTaskId,
            UUID runId,
            UUID goalId,
            ResearchPhase phase,
            UUID chunkId,
            int rank,
            double score,
            String retrievalSource,
            Integer mergedRank,
            Double rerankScore
    ) {
    }

    public record BudgetReservation(
            UUID id,
            UUID runId,
            String actionKey,
            Map<String, Long> reservedUsage,
            Map<String, Long> actualUsage,
            boolean usageEstimated,
            ReservationStatus status
    ) {
    }

    public record ExternalAction(
            UUID id,
            UUID runId,
            UUID goalId,
            String phase,
            String operation,
            UUID reservationId,
            ActionStatus status,
            String errorCategory
    ) {
    }

    public record LogicalModelCall(
            UUID id,
            UUID runId,
            UUID goalId,
            String phase,
            String operation,
            String promptVersion,
            String contractVersion,
            String promptHash,
            int promptLength,
            int attemptCount,
            boolean repairUsed,
            long inputTokens,
            long outputTokens,
            long latencyMs,
            ActionStatus status,
            String errorCategory,
            String resultHash
    ) {
    }

    public record ModelAttempt(
            UUID id,
            UUID logicalCallId,
            int attemptNumber,
            UUID reservationId,
            ActionStatus status,
            long inputTokens,
            long outputTokens,
            boolean tokenUsageEstimated,
            long latencyMs,
            String errorCategory
    ) {
    }

    public record RecoveryState(
            List<GoalResearchOutcome> goalOutcomes,
            List<BudgetReservation> reservations,
            List<ExternalAction> actions,
            List<LogicalModelCall> logicalCalls,
            List<ModelAttempt> attempts
    ) {
    }
}
