package com.yanyue.rag.application.chat.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.chat.v4.AgenticV4ModelInvoker;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;
import com.yanyue.rag.domain.agent.v5.GoalPlan;
import com.yanyue.rag.domain.agent.v5.SearchQuery;
import com.yanyue.rag.domain.agent.v8.AgenticV8Limits;
import com.yanyue.rag.domain.chunking.v4.CandidateSpan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DeepReadReasonerV5 {
    private final AgenticV4ModelInvoker invoker;
    private final ObjectMapper objectMapper;
    private final String promptV5 = resource("prompts/agentic-v5-deep-read.md");
    private final String promptV8 = resource("prompts/agentic-v8-deep-read.md");

    public DeepReadReasonerV5(AgenticV4ModelInvoker invoker, ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.objectMapper = objectMapper;
    }

    public List<Selection> select(
            UUID profileId,
            UUID runId,
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            Set<UUID> targetRequirementIds,
            List<SearchQuery> queries,
            List<CandidateSpan> spans,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits
    ) {
        return select(profileId, runId, objective, goal, phase, targetRequirementIds, queries, spans,
                ledger, limits, null);
    }

    /**
     * Execute a bounded Deep Read with an optional action suffix. The suffix
     * keeps document-local read-more calls distinct from the later repair
     * retrieval Deep Read for the same Goal and phase.
     */
    public List<Selection> select(
            UUID profileId,
            UUID runId,
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            Set<UUID> targetRequirementIds,
            List<SearchQuery> queries,
            List<CandidateSpan> spans,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            String actionSuffix
    ) {
        return selectUntil(profileId, runId, objective, goal, phase, targetRequirementIds, queries, spans,
                ledger, limits, actionSuffix, ledger.deadline());
    }

    public List<Selection> selectUntil(
            UUID profileId,
            UUID runId,
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            Set<UUID> targetRequirementIds,
            List<SearchQuery> queries,
            List<CandidateSpan> spans,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            String actionSuffix,
            java.time.Instant operationDeadline
    ) {
        if (spans.isEmpty()) return List.of();
        var prompt = AgenticV8Limits.matches(limits) ? promptV8 : promptV5;
        var offered = new ArrayList<>(spans.stream().limit(limits.retrieval().candidateSpanLimit()).toList());
        var input = input(objective, goal, phase, targetRequirementIds, queries, offered);
        while (requestTokens(prompt, input) > limits.tokens().deepReadInput() && offered.size() > 1) {
            offered.removeLast();
            input = input(objective, goal, phase, targetRequirementIds, queries, offered);
        }
        if (requestTokens(prompt, input) > limits.tokens().deepReadInput()) {
            throw new IllegalStateException("Deep Read 输入超过 Token 上限");
        }
        var offeredSpanIds = offered.stream().map(CandidateSpan::spanId).collect(java.util.stream.Collectors.toSet());
        var finalInput = input;
        try {
            var dimension = phase == ResearchPhase.PRIMARY
                    ? BudgetDimension.PRIMARY_DEEP_READ_CALL : BudgetDimension.REPAIR_DEEP_READ_CALL;
            var actionKey = "deep-read:" + phase + ":" + goal.id()
                    + (actionSuffix == null || actionSuffix.isBlank() ? "" : ":" + actionSuffix);
            var promptVersion = AgenticV8Limits.matches(limits)
                    ? "agentic-v8-deep-read-v1" : "agentic-v5-deep-read";
            return invoker.invokeJson(profileId, runId, actionKey,
                    promptVersion, prompt, objectMapper.writeValueAsString(finalInput),
                    limits.tokens().deepReadOutput(), dimension, ledger, operationDeadline,
                    raw -> validate(parse(raw), offeredSpanIds, targetRequirementIds));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Deep Read 输入无法序列化", failure);
        }
    }

    List<Selection> parse(String raw) {
        try {
            var root = objectMapper.readTree(raw);
            var values = root.path("selections");
            if (!values.isArray()) throw new IllegalStateException("selections 必须是数组");
            var result = new ArrayList<Selection>();
            for (var value : values) {
                var requirementIds = uuids(value.path("requirementIds"));
                if (requirementIds.isEmpty()) continue;
                result.add(new Selection(requiredText(value, "spanId"), requirementIds));
            }
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("v5 Deep Read 输出不合法", failure);
        }
    }

    private List<Selection> validate(
            List<Selection> selections,
            Set<String> offeredSpanIds,
            Set<UUID> offeredRequirementIds
    ) {
        // Structured output from a low-reasoning model can repeat a span or
        // include one stale requirement while still containing valid evidence.
        // Keep the valid subset instead of failing the entire Goal Deep Read.
        var normalized = new LinkedHashMap<String, LinkedHashSet<UUID>>();
        for (var selection : selections) {
            if (!offeredSpanIds.contains(selection.spanId())) continue;
            var validRequirements = selection.requirementIds().stream()
                    .filter(offeredRequirementIds::contains)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (validRequirements.isEmpty()) continue;
            normalized.computeIfAbsent(selection.spanId(), ignored -> new LinkedHashSet<>())
                    .addAll(validRequirements);
        }
        return normalized.entrySet().stream()
                .map(value -> new Selection(value.getKey(), value.getValue()))
                .toList();
    }

    private Map<String, Object> input(
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            Set<UUID> targetRequirementIds,
            List<SearchQuery> queries,
            List<CandidateSpan> spans
    ) {
        var input = new LinkedHashMap<String, Object>();
        input.put("objective", objective);
        input.put("goal", Map.of("goalId", goal.id(), "question", goal.question()));
        input.put("phase", phase);
        input.put("targetRequirements", goal.requirements().stream()
                .filter(value -> targetRequirementIds.contains(value.id()))
                .map(value -> Map.of("requirementId", value.id(), "description", value.description())).toList());
        input.put("queries", queries.stream().map(query -> Map.of(
                "queryId", query.queryId(), "role", query.role(), "searchMode", query.searchMode(),
                "text", query.text())).toList());
        input.put("candidateSpans", spans.stream().map(span -> Map.of(
                "spanId", span.spanId(), "titlePath", span.titlePath(), "text", span.text())).toList());
        return input;
    }

    private int requestTokens(String prompt, Map<String, Object> input) {
        try {
            return AgenticV4ModelInvoker.estimatedTokens(prompt)
                    + AgenticV4ModelInvoker.estimatedTokens(objectMapper.writeValueAsString(input));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Deep Read 输入无法序列化", failure);
        }
    }

    private Set<UUID> uuids(JsonNode value) {
        if (!value.isArray()) throw new IllegalStateException("requirementIds 必须是数组");
        var result = new LinkedHashSet<UUID>();
        value.forEach(item -> result.add(UUID.fromString(item.asText())));
        return Set.copyOf(result);
    }

    private String requiredText(JsonNode node, String field) {
        var value = node.path(field).asText("").strip();
        if (value.isEmpty()) throw new IllegalStateException(field + " 不能为空");
        return value;
    }

    private static String resource(String path) {
        try (var input = DeepReadReasonerV5.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("缺少 Prompt: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt 无法读取: " + path, exception);
        }
    }

    public record Selection(String spanId, Set<UUID> requirementIds) {
        public Selection {
            requirementIds = Set.copyOf(requirementIds);
        }
    }
}
