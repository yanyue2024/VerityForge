package com.yanyue.rag.contract.knowledge;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StartIndexRebuildRequest(@NotNull UUID embeddingProfileId) {
}
