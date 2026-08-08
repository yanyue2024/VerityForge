package com.yanyue.rag.domain.agent.v5;

import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import java.util.LinkedHashSet;
import java.util.Locale;
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
        V5Validation.required(queryId, "queryId");
        V5Validation.required(goalId, "goalId");
        V5Validation.required(phase, "phase");
        V5Validation.required(role, "role");
        text = V5Validation.requiredText(text, "text", MAX_TEXT_LENGTH);
        V5Validation.required(searchMode, "searchMode");
        V5Validation.required(targetRequirementIds, "targetRequirementIds");
        if (targetRequirementIds.isEmpty() || targetRequirementIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("targetRequirementIds must contain known requirements");
        }
        targetRequirementIds = Set.copyOf(new LinkedHashSet<>(targetRequirementIds));
        validateProtocol(phase, role, searchMode);
    }

    public String normalizedText() {
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static void validateProtocol(ResearchPhase phase, SearchQueryRole role, SearchMode mode) {
        boolean valid = switch (role) {
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
