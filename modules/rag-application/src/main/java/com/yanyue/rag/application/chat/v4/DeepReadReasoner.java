package com.yanyue.rag.application.chat.v4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.GoalPlan;
import com.yanyue.rag.domain.agent.v4.RepairTarget;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchQuery;
import com.yanyue.rag.domain.agent.v4.TargetEffect;
import com.yanyue.rag.domain.chunking.v4.CandidateSpan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DeepReadReasoner {
    private final AgenticV4ModelInvoker invoker;
    private final ObjectMapper objectMapper;
    private final String prompt = resource("prompts/agentic-v4-deep-read.md");

    public DeepReadReasoner(AgenticV4ModelInvoker invoker, ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.objectMapper = objectMapper;
    }

    public List<Selection> select(
            UUID profileId,
            UUID runId,
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            List<UUID> targetRequirementIds,
            List<RepairTarget> repairTargets,
            List<SearchQuery> queries,
            List<CandidateSpan> spans,
            AgentBudgetLedger ledger
    ) {
        if (spans.isEmpty()) return List.of();
        var offeredSpans = new ArrayList<>(spans);
        var input = input(objective, goal, phase, targetRequirementIds, repairTargets, queries, offeredSpans);
        while (requestTokens(input) > 6_000 && !offeredSpans.isEmpty()) {
            offeredSpans.removeLast();
            input = input(objective, goal, phase, targetRequirementIds, repairTargets, queries, offeredSpans);
        }
        if (offeredSpans.isEmpty()) {
            throw new IllegalStateException("Deep Read 元数据超过单次输入 Token 上限");
        }
        var offeredSpanIds = offeredSpans.stream().map(CandidateSpan::spanId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var offeredRequirementIds = Set.copyOf(targetRequirementIds);
        var finalInput = input;
        try {
            var dimension = phase == ResearchPhase.PRIMARY
                    ? BudgetDimension.PRIMARY_DEEP_READ_CALL : BudgetDimension.REPAIR_DEEP_READ_CALL;
            return invoker.invokeJson(profileId, runId, "deep-read:" + phase + ":" + goal.id(),
                    "agentic-v4-deep-read", prompt, objectMapper.writeValueAsString(finalInput), 500,
                    dimension, ledger, raw -> validate(parse(raw), offeredSpanIds, offeredRequirementIds));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Deep Read 输入无法序列化", failure);
        }
    }

    private Map<String, Object> input(
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            List<UUID> targetRequirementIds,
            List<RepairTarget> repairTargets,
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
        input.put("repairTargets", repairTargets.stream().map(target -> Map.of(
                "repairTargetId", target.id(), "requirementId", target.requirementId(),
                "description", target.description(), "completionMode", target.completionMode())).toList());
        input.put("queries", queries.stream().map(query -> Map.of(
                "queryId", query.queryId(), "text", query.text(), "searchMode", query.searchMode())).toList());
        input.put("candidateSpans", spans.stream().map(span -> Map.of(
                "spanId", span.spanId(), "text", span.text())).toList());
        return input;
    }

    private int requestTokens(Map<String, Object> input) {
        try {
            return AgenticV4ModelInvoker.estimatedTokens(prompt)
                    + AgenticV4ModelInvoker.estimatedTokens(objectMapper.writeValueAsString(input));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Deep Read 输入无法序列化", failure);
        }
    }

    private List<Selection> validate(
            List<Selection> selections,
            Set<String> offeredSpanIds,
            Set<UUID> offeredRequirementIds
    ) {
        for (var selection : selections) {
            if (!offeredSpanIds.contains(selection.spanId()) || selection.supports().isEmpty()
                    || selection.supports().stream().anyMatch(
                            support -> !offeredRequirementIds.contains(support.requirementId()))) {
                throw new IllegalStateException("Deep Read 引用了未提供的 Span 或 Requirement");
            }
        }
        return selections;
    }

    List<Selection> parse(String raw) {
        try {
            var root = objectMapper.readTree(raw);
            var values = root.path("selections");
            if (!values.isArray()) throw new IllegalStateException("selections 必须是数组");
            var result = new ArrayList<Selection>();
            for (var value : values) {
                var supports = new ArrayList<Support>();
                var supportValues = value.path("supports");
                if (!supportValues.isArray() || supportValues.isEmpty()) continue;
                for (var support : supportValues) {
                    supports.add(new Support(UUID.fromString(requiredText(support, "requirementId")),
                            nullableUuid(support.path("repairTargetId")),
                            nullableEffect(support.path("targetEffect"))));
                }
                result.add(new Selection(requiredText(value, "spanId"), supports));
            }
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Deep Read 输出不合法", failure);
        }
    }

    private UUID nullableUuid(JsonNode value) {
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null : UUID.fromString(value.asText());
    }

    private TargetEffect nullableEffect(JsonNode value) {
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null : TargetEffect.valueOf(value.asText());
    }

    private String requiredText(JsonNode node, String field) {
        var value = node.path(field).asText("").strip();
        if (value.isEmpty()) throw new IllegalStateException(field + " 不能为空");
        return value;
    }

    private static String resource(String path) {
        try (var input = DeepReadReasoner.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("缺少 Prompt: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt 无法读取: " + path, exception);
        }
    }

    public record Selection(String spanId, List<Support> supports) {
        public Selection {
            supports = supports == null ? List.of() : List.copyOf(supports);
        }
    }

    public record Support(UUID requirementId, UUID repairTargetId, TargetEffect targetEffect) { }
}
