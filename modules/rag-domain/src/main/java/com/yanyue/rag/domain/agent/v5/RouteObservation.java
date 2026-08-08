package com.yanyue.rag.domain.agent.v5;

import com.yanyue.rag.domain.agent.v4.SearchMode;
import java.util.UUID;

public record RouteObservation(UUID queryId, SearchMode searchMode, int rawRank, double rawScore) {
    public RouteObservation {
        V5Validation.required(queryId, "queryId");
        V5Validation.required(searchMode, "searchMode");
        // v8 can deliberately inspect a wider top-60 route. Keep this domain
        // boundary above every supported profile while still rejecting invalid
        // ranks from adapters.
        V5Validation.positiveAtMost(rawRank, 100, "raw rank");
        if (!Double.isFinite(rawScore)) {
            throw new IllegalArgumentException("rawScore must be finite");
        }
    }
}
