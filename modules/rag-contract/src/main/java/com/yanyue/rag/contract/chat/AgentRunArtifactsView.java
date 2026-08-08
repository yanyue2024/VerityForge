package com.yanyue.rag.contract.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AgentRunArtifactsView(
        UUID runId,
        String status,
        RunMode requestedMode,
        RunMode selectedMode,
        String query,
        Map<String, Object> runtimeSnapshot,
        Map<String, Object> checkpoint,
        List<Map<String, Object>> retrievalTasks,
        List<Map<String, Object>> evidence,
        List<Map<String, Object>> facts,
        List<Map<String, Object>> coverage,
        int artifactVersion,
        List<Map<String, Object>> reactSteps,
        List<Map<String, Object>> toolCalls,
        List<Map<String, Object>> knowledgeReferences,
        List<Map<String, Object>> rankedDocuments,
        Map<String, Object> budgetSnapshot
) {
    public AgentRunArtifactsView(
            UUID runId, String status, RunMode requestedMode, RunMode selectedMode, String query,
            Map<String, Object> runtimeSnapshot, Map<String, Object> checkpoint,
            List<Map<String, Object>> retrievalTasks, List<Map<String, Object>> evidence,
            List<Map<String, Object>> facts, List<Map<String, Object>> coverage
    ) {
        this(runId, status, requestedMode, selectedMode, query, runtimeSnapshot, checkpoint,
                retrievalTasks, evidence, facts, coverage, 1, List.of(), List.of(), List.of(), List.of(), Map.of());
    }
}
