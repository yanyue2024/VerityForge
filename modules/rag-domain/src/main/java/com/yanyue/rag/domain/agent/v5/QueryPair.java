package com.yanyue.rag.domain.agent.v5;

import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import java.util.List;
import java.util.UUID;

public record QueryPair(
        UUID goalId,
        ResearchPhase phase,
        SearchQuery keywordQuery,
        SearchQuery semanticQuery
) {
    public QueryPair {
        V5Validation.required(goalId, "goalId");
        V5Validation.required(phase, "phase");
        V5Validation.required(keywordQuery, "keywordQuery");
        V5Validation.required(semanticQuery, "semanticQuery");
        if (!goalId.equals(keywordQuery.goalId()) || !goalId.equals(semanticQuery.goalId())
                || phase != keywordQuery.phase() || phase != semanticQuery.phase()) {
            throw new IllegalArgumentException("query pair members must belong to the same goal and phase");
        }
        var expectedKeywordRole = phase == ResearchPhase.PRIMARY
                ? SearchQueryRole.PRIMARY_KEYWORD : SearchQueryRole.REPAIR_KEYWORD;
        var expectedSemanticRole = phase == ResearchPhase.PRIMARY
                ? SearchQueryRole.PRIMARY_SEMANTIC : SearchQueryRole.REPAIR_SEMANTIC;
        if (keywordQuery.role() != expectedKeywordRole || keywordQuery.searchMode() != SearchMode.KEYWORD
                || semanticQuery.role() != expectedSemanticRole || semanticQuery.searchMode() != SearchMode.SEMANTIC) {
            throw new IllegalArgumentException("query pair must contain one mode-specific query per route");
        }
        if (keywordQuery.queryId().equals(semanticQuery.queryId())) {
            throw new IllegalArgumentException("query pair members must have different IDs");
        }
        if (!keywordQuery.targetRequirementIds().equals(semanticQuery.targetRequirementIds())) {
            throw new IllegalArgumentException("query pair members must target the same requirements");
        }
        if (keywordQuery.normalizedText().equals(semanticQuery.normalizedText())) {
            throw new IllegalArgumentException("query pair texts must be independently designed for each route");
        }
    }

    public List<SearchQuery> queries() {
        return List.of(keywordQuery, semanticQuery);
    }
}
