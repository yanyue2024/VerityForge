package com.yanyue.rag.domain.agent.v5;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CoverageState(
        Map<UUID, RequirementStatus> requirementStatuses,
        Map<UUID, GoalStatus> goalStatuses,
        boolean judgeDegraded
) {
    public CoverageState {
        requirementStatuses = immutableStatuses(requirementStatuses, "requirementStatuses");
        goalStatuses = immutableStatuses(goalStatuses, "goalStatuses");
    }

    public List<UUID> incompleteGoalIds() {
        return goalStatuses.entrySet().stream()
                .filter(entry -> entry.getValue() != GoalStatus.SATISFIED_LOCKED)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * v5 补检只执行一次；结束后保留 Judge 的 Requirement 状态，不制造第二次覆盖结论。
     */
    public CoverageState afterRepair() {
        if (goalStatuses.values().stream().noneMatch(status -> status == GoalStatus.NEEDS_REPAIR)) {
            throw new IllegalStateException("coverage state has no goal awaiting repair");
        }
        var updated = new LinkedHashMap<>(goalStatuses);
        updated.replaceAll((ignored, status) -> status == GoalStatus.NEEDS_REPAIR
                ? GoalStatus.REPAIR_EXHAUSTED : status);
        return new CoverageState(requirementStatuses, updated, judgeDegraded);
    }

    private static <T> Map<UUID, T> immutableStatuses(Map<UUID, T> values, String field) {
        V5Validation.required(values, field);
        if (values.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException(field + " contains null key or value");
        }
        return Map.copyOf(values);
    }
}
