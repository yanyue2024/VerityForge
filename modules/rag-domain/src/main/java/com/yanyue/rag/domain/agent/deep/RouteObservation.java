package com.yanyue.rag.domain.agent.deep;

import com.yanyue.rag.domain.agent.deep.SearchMode;
import java.util.UUID;

public record RouteObservation(UUID queryId, SearchMode searchMode, int rawRank, double rawScore) {
    public RouteObservation {
        DeepValidation.required(queryId, "queryId");
        DeepValidation.required(searchMode, "searchMode");
        // The final profile can deliberately inspect a wider top-60 route. Keep this domain
        // boundary above every supported profile while still rejecting invalid
        // ranks from adapters.
        DeepValidation.positiveAtMost(rawRank, 100, "raw rank");
        if (!Double.isFinite(rawScore)) {
            throw new IllegalArgumentException("rawScore must be finite");
        }
    }
}
