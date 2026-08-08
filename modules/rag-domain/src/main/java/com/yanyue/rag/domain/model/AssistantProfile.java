package com.yanyue.rag.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssistantProfile(
        UUID id,
        UUID organizationId,
        int version,
        Status status,
        String assistantName,
        String identity,
        List<String> capabilities,
        String tone,
        List<String> boundaries,
        String additionalInstructions,
        Instant previewedAt,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public AssistantProfile {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        boundaries = boundaries == null ? List.of() : List.copyOf(boundaries);
        additionalInstructions = additionalInstructions == null ? "" : additionalInstructions;
    }

    public enum Status { DRAFT, PUBLISHED, ARCHIVED }

    public String roleInstruction() {
        var value = new StringBuilder();
        value.append("你的名字是 ").append(assistantName).append("。\n");
        value.append(identity.strip()).append("\n");
        if (!capabilities.isEmpty()) value.append("你可以：\n- ").append(String.join("\n- ", capabilities)).append("\n");
        value.append("表达风格：").append(tone.strip()).append("\n");
        if (!boundaries.isEmpty()) value.append("行为边界：\n- ").append(String.join("\n- ", boundaries)).append("\n");
        if (!additionalInstructions.isBlank()) value.append("补充要求：\n").append(additionalInstructions.strip()).append("\n");
        return value.toString().strip();
    }
}
