package com.yanyue.rag.contract.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvaluationDatasetBundle(
        @NotBlank String schemaVersion,
        UUID sourceDatasetId,
        Instant exportedAt,
        @NotBlank @Size(max = 160) String name,
        @NotNull @Size(max = 4000) String description,
        @NotNull @Size(min = 1, max = 500) List<@Valid CaseEntry> cases
) {
    public static final String SCHEMA_VERSION = "rag-evaluation-dataset/v1";

    public EvaluationDatasetBundle {
        cases = cases == null ? List.of() : List.copyOf(cases);
        description = description == null ? "" : description;
    }

    public record CaseEntry(
            @NotBlank @Size(max = 8000) String question,
            @Size(max = 16000) String expectedAnswer,
            @NotNull @Size(max = 50) List<@NotNull UUID> expectedDocumentIds,
            @NotNull Map<String, Object> metadata
    ) {
        public CaseEntry {
            expectedDocumentIds = expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
