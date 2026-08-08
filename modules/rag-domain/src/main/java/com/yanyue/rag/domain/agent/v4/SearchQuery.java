package com.yanyue.rag.domain.agent.v4;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record SearchQuery(
        UUID queryId,
        UUID goalId,
        ResearchPhase phase,
        SearchQueryRole role,
        String text,
        SearchMode searchMode,
        Set<UUID> targetRequirementIds
) {
    public static final int MAX_TEXT_LENGTH = 300;

    public SearchQuery {
        V4Validation.required(queryId, "queryId");
        V4Validation.required(goalId, "goalId");
        V4Validation.required(phase, "phase");
        V4Validation.required(role, "role");
        text = V4Validation.requiredText(text, "text");
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("query text exceeds 300 characters");
        }
        V4Validation.required(searchMode, "searchMode");
        targetRequirementIds = Set.copyOf(new LinkedHashSet<>(
                V4Validation.required(targetRequirementIds, "targetRequirementIds")));
        if (targetRequirementIds.isEmpty()) {
            throw new IllegalArgumentException("targetRequirementIds must not be empty");
        }
        validateProtocol(phase, role, searchMode);
    }

    private static void validateProtocol(ResearchPhase phase, SearchQueryRole role, SearchMode mode) {
        boolean valid = switch (role) {
            case INITIAL -> phase == ResearchPhase.PRIMARY;
            case PRIMARY_KEYWORD -> phase == ResearchPhase.PRIMARY && mode == SearchMode.KEYWORD;
            case PRIMARY_SEMANTIC -> phase == ResearchPhase.PRIMARY && mode == SearchMode.SEMANTIC;
            case REPAIR_KEYWORD -> phase == ResearchPhase.REPAIR && mode == SearchMode.KEYWORD;
            case REPAIR_SEMANTIC -> phase == ResearchPhase.REPAIR && mode == SearchMode.SEMANTIC;
        };
        if (!valid) {
            throw new IllegalArgumentException("query role, phase and search mode are inconsistent");
        }
    }
}
