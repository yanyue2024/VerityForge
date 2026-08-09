package com.yanyue.rag.domain.agent.deep;

import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.SearchMode;
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
        DeepValidation.required(queryId, "queryId");
        DeepValidation.required(goalId, "goalId");
        DeepValidation.required(phase, "phase");
        DeepValidation.required(role, "role");
        text = DeepValidation.requiredText(text, "text", MAX_TEXT_LENGTH);
        DeepValidation.required(searchMode, "searchMode");
        DeepValidation.required(targetRequirementIds, "targetRequirementIds");
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
