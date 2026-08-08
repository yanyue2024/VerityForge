package com.yanyue.rag.domain.agent.react;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReactRunArtifacts(
        UUID runId,
        int artifactVersion,
        ReactCheckpoint checkpoint,
        List<ReactStep> steps,
        List<ReactToolCall> toolCalls,
        List<ReactKnowledgeReference> knowledgeReferences,
        List<ReactRankedDocument> rankedDocuments,
        Map<String, Object> budgetSnapshot
) {
    public ReactRunArtifacts {
        if (artifactVersion < 1) throw new IllegalArgumentException("artifactVersion must be positive");
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        knowledgeReferences = knowledgeReferences == null ? List.of() : List.copyOf(knowledgeReferences);
        rankedDocuments = rankedDocuments == null ? List.of() : List.copyOf(rankedDocuments);
        budgetSnapshot = budgetSnapshot == null ? Map.of() : Map.copyOf(budgetSnapshot);
    }
}
