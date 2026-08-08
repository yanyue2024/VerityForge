package com.yanyue.rag.domain.agent.v4;

import java.util.UUID;

public record RepairTarget(
        UUID id,
        UUID goalId,
        UUID requirementId,
        String description,
        RepairCompletionMode completionMode,
        RepairTargetStatus status
) {
    public RepairTarget {
        V4Validation.required(id, "id");
        V4Validation.required(goalId, "goalId");
        V4Validation.required(requirementId, "requirementId");
        description = V4Validation.requiredText(description, "description", 500);
        V4Validation.required(completionMode, "completionMode");
        V4Validation.required(status, "status");
    }

    public static RepairTarget open(UUID id, UUID goalId, UUID requirementId, String description,
                                    RepairCompletionMode completionMode) {
        return new RepairTarget(id, goalId, requirementId, description, completionMode, RepairTargetStatus.OPEN);
    }

    public RepairTarget apply(TargetEffect effect) {
        V4Validation.required(effect, "effect");
        if (status != RepairTargetStatus.OPEN) {
            throw new IllegalStateException("closed repair target cannot accept evidence");
        }
        if (completionMode == RepairCompletionMode.REVIEW_REQUIRED && effect == TargetEffect.COMPLETE) {
            throw new IllegalArgumentException("review-required target cannot be completed without another judge");
        }
        return effect == TargetEffect.COMPLETE
                ? withStatus(RepairTargetStatus.SATISFIED)
                : this;
    }

    public RepairTarget exhaust() {
        if (status != RepairTargetStatus.OPEN) {
            throw new IllegalStateException("only an open repair target can be exhausted");
        }
        return withStatus(RepairTargetStatus.EXHAUSTED);
    }

    private RepairTarget withStatus(RepairTargetStatus targetStatus) {
        return new RepairTarget(id, goalId, requirementId, description, completionMode, targetStatus);
    }
}
