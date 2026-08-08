package com.yanyue.rag.domain.agent;

import java.util.List;
import java.util.UUID;

public record SubQuestionCoverage(
        UUID subQuestionId,
        boolean covered,
        int deepReadEvidenceFamilies,
        List<String> gaps,
        boolean hasConflict,
        List<SupportedSurface> supportedSurfaces
) {
    public SubQuestionCoverage {
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        supportedSurfaces = supportedSurfaces == null ? List.of() : List.copyOf(supportedSurfaces);
    }

    public SubQuestionCoverage(
            UUID subQuestionId,
            boolean covered,
            int deepReadEvidenceFamilies,
            List<String> gaps,
            boolean hasConflict
    ) {
        this(subQuestionId, covered, deepReadEvidenceFamilies, gaps, hasConflict, List.of());
    }
}
