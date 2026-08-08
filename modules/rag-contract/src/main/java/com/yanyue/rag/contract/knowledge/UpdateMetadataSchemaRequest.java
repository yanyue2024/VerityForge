package com.yanyue.rag.contract.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateMetadataSchemaRequest(
        @NotNull @Size(max = 100) List<@Valid MetadataFieldRequest> fields
) {
    public UpdateMetadataSchemaRequest {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
