package com.yanyue.rag.domain.agent.v4;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record RequirementState(UUID requirementId, RequirementStatus status) {
    private static final Map<RequirementStatus, Set<RequirementStatus>> TRANSITIONS = Map.of(
            RequirementStatus.UNASSESSED, EnumSet.of(RequirementStatus.COVERED, RequirementStatus.MISSING,
                    RequirementStatus.CONFLICTING, RequirementStatus.NOT_FOUND_WITHIN_BUDGET),
            RequirementStatus.MISSING, EnumSet.of(RequirementStatus.COVERED,
                    RequirementStatus.NOT_FOUND_WITHIN_BUDGET),
            RequirementStatus.CONFLICTING, EnumSet.of(RequirementStatus.CONFLICTING),
            RequirementStatus.COVERED, EnumSet.of(RequirementStatus.COVERED),
            RequirementStatus.NOT_FOUND_WITHIN_BUDGET, EnumSet.of(RequirementStatus.NOT_FOUND_WITHIN_BUDGET)
    );

    public RequirementState {
        V4Validation.required(requirementId, "requirementId");
        V4Validation.required(status, "status");
    }

    public static RequirementState unassessed(UUID requirementId) {
        return new RequirementState(requirementId, RequirementStatus.UNASSESSED);
    }

    public RequirementState transitionTo(RequirementStatus target) {
        V4Validation.required(target, "target");
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new IllegalStateException("illegal requirement transition from " + status + " to " + target);
        }
        return new RequirementState(requirementId, target);
    }
}
