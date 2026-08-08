package com.yanyue.rag.application.chat.v8;

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
import com.yanyue.rag.domain.chunking.TokenEstimator;
import com.yanyue.rag.domain.chunking.v4.CandidateSpan;
import com.yanyue.rag.domain.chunking.v4.CandidateSpanBuilder;
import com.yanyue.rag.domain.chunking.v4.ChunkSourceSegment;
import com.yanyue.rag.domain.chunking.v4.ParentContext;
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
public class ParentDeepReadReasonerV8 {
    private static final int MAX_ADAPTIVE_EVIDENCE_PER_PARENT = 3;

    private final AgenticV4ModelInvoker invoker;
    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator = new TokenEstimator();
    private final String adaptivePrompt = resource("prompts/agentic-v8-adaptive-parent-read.md");
    private final String parentPrompt = resource("prompts/agentic-v8-parent-acceptance.md");
    private final String batchedParentPrompt = resource("prompts/agentic-v8-goal-batched-parent-read.md");

    public ParentDeepReadReasonerV8(AgenticV4ModelInvoker invoker, ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.objectMapper = objectMapper;
    }

    public List<AdaptiveSelection> extractEvidence(
            UUID profileId,
            UUID runId,
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            Set<UUID> targetRequirementIds,
            List<SearchQuery> queries,
            ParentContext parent,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            java.time.Instant operationDeadline
    ) {
        var sourceBlocks = sourceBlocks(parent);
        if (sourceBlocks.isEmpty()) return List.of();
        var input = input(objective, goal, phase, targetRequirementIds, queries, parent,
                Map.of("sourceBlocks", sourceBlocks.stream().map(block -> Map.of(
                        "blockId", block.id(), "text", block.text())).toList()));
        ensureInputBudget(adaptivePrompt, input, limits);
        var dimension = phase == ResearchPhase.PRIMARY
                ? BudgetDimension.PRIMARY_DEEP_READ_CALL : BudgetDimension.REPAIR_DEEP_READ_CALL;
        try {
            return invoker.invokeJson(profileId, runId,
                    actionKey(phase, goal.id(), "adaptive", parent.parentChunkId()),
                    "agentic-v8-adaptive-parent-read-v1", adaptivePrompt,
                    objectMapper.writeValueAsString(input),
                    Math.min(500, limits.tokens().deepReadOutput()), dimension, ledger, operationDeadline,
                    raw -> parseAdaptive(raw, parent, sourceBlocks, targetRequirementIds));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Adaptive Deep Read 输入无法序列化", failure);
        }
    }

    public ParentSelection acceptParent(
            UUID profileId,
            UUID runId,
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            Set<UUID> targetRequirementIds,
            List<SearchQuery> queries,
            ParentContext parent,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            java.time.Instant operationDeadline
    ) {
        var input = input(objective, goal, phase, targetRequirementIds, queries, parent,
                Map.of("parentText", parent.text()));
        ensureInputBudget(parentPrompt, input, limits);
        var dimension = phase == ResearchPhase.PRIMARY
                ? BudgetDimension.PRIMARY_DEEP_READ_CALL : BudgetDimension.REPAIR_DEEP_READ_CALL;
        try {
            return invoker.invokeJson(profileId, runId,
                    actionKey(phase, goal.id(), "parent", parent.parentChunkId()),
                    "agentic-v8-parent-acceptance-v1", parentPrompt,
                    objectMapper.writeValueAsString(input),
                    Math.min(220, limits.tokens().deepReadOutput()), dimension, ledger, operationDeadline,
                    raw -> parseParent(raw, targetRequirementIds));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Parent acceptance Deep Read 输入无法序列化", failure);
        }
    }

    public List<BatchedParentSelection> reviewParents(
            UUID profileId,
            UUID runId,
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            Set<UUID> targetRequirementIds,
            List<SearchQuery> queries,
            List<ParentContext> parents,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            java.time.Instant operationDeadline
    ) {
        if (parents == null || parents.isEmpty()) return List.of();
        var input = batchInput(objective, goal, phase, targetRequirementIds, queries, parents);
        ensureInputBudget(batchedParentPrompt, input, limits);
        var dimension = phase == ResearchPhase.PRIMARY
                ? BudgetDimension.PRIMARY_DEEP_READ_CALL : BudgetDimension.REPAIR_DEEP_READ_CALL;
        try {
            return invoker.invokeJson(profileId, runId,
                    "deep-read:" + phase + ":" + goal.id() + ":goal-batch",
                    "agentic-v8-goal-batched-parent-read-v4", batchedParentPrompt,
                    objectMapper.writeValueAsString(input), Math.min(
                            AgenticV8Limits.BATCHED_PARENT_DEEP_READ_OUTPUT_TOKENS,
                            limits.tokens().deepReadOutput()),
                    dimension, ledger,
                    operationDeadline, raw -> parseBatch(raw, parents, targetRequirementIds));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Goal 批量父块 Deep Read 输入无法序列化", failure);
        }
    }

    List<AdaptiveSelection> parseAdaptive(
            String raw,
            ParentContext parent,
            List<SourceBlock> sourceBlocks,
            Set<UUID> offeredRequirementIds
    ) {
        try {
            var values = objectMapper.readTree(raw).path("evidence");
            if (!values.isArray()) throw new IllegalStateException("evidence 必须是数组");
            var blocks = sourceBlocks.stream().collect(java.util.stream.Collectors.toMap(
                    SourceBlock::id, value -> value, (left, right) -> left, LinkedHashMap::new));
            var unique = new LinkedHashMap<String, AdaptiveSelection>();
            for (var value : values) {
                var block = blocks.get(value.path("blockId").asText(""));
                var quote = value.path("quote").asText("").strip();
                var requirementIds = validRequirementIds(value.path("requirementIds"), offeredRequirementIds);
                if (block == null || quote.isEmpty() || requirementIds.isEmpty()) continue;
                int relativeStart = block.text().indexOf(quote);
                if (relativeStart < 0) continue;
                int start = block.segment().chunkLocalStart() + relativeStart;
                int end = start + quote.length();
                var anchor = parent.sourceMap().anchorFor(parent.documentVersionId(), start, end).orElse(null);
                int estimatedTokens = tokenEstimator.estimate(quote);
                if (anchor == null || estimatedTokens < 1
                        || estimatedTokens > CandidateSpanBuilder.MAX_SPAN_TOKENS) continue;
                var spanId = CandidateSpanBuilder.stableSpanId(parent.documentVersionId(),
                        parent.parentChunkId(), start, end, CandidateSpanBuilder.textHash(parent.text()));
                var span = new CandidateSpan(spanId, parent.parentChunkId(), start, end, quote,
                        parent.titlePath(), anchor, estimatedTokens, 0, 0);
                unique.merge(spanId, new AdaptiveSelection(span, requirementIds),
                        (left, right) -> new AdaptiveSelection(left.span(), union(
                                left.requirementIds(), right.requirementIds())));
                if (unique.size() >= MAX_ADAPTIVE_EVIDENCE_PER_PARENT) break;
            }
            return List.copyOf(unique.values());
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Adaptive Deep Read 输出不合法", failure);
        }
    }

    ParentSelection parseParent(String raw, Set<UUID> offeredRequirementIds) {
        try {
            var root = objectMapper.readTree(raw);
            if (!root.has("accepted") || !root.path("accepted").isBoolean()) {
                throw new IllegalStateException("accepted 必须是布尔值");
            }
            if (!root.path("accepted").asBoolean()) return new ParentSelection(false, Set.of());
            var requirementIds = validRequirementIds(root.path("requirementIds"), offeredRequirementIds);
            return new ParentSelection(!requirementIds.isEmpty(), requirementIds);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Parent acceptance Deep Read 输出不合法", failure);
        }
    }

    List<BatchedParentSelection> parseBatch(
            String raw,
            List<ParentContext> parents,
        Set<UUID> offeredRequirementIds
    ) {
        try {
            var root = objectMapper.readTree(raw);
            var values = root.has("acceptedParents")
                    ? root.path("acceptedParents")
                    : root.path("parentDecisions");
            if (!values.isArray()) throw new IllegalStateException("acceptedParents 必须是数组");
            var offeredParents = parents.stream().collect(java.util.stream.Collectors.toMap(
                    ParentContext::parentChunkId, value -> value, (left, right) -> left, LinkedHashMap::new));
            var accepted = new LinkedHashMap<UUID, Set<UUID>>();
            for (var value : values) {
                UUID parentId;
                try {
                    parentId = UUID.fromString(value.path("parentChunkId").asText(""));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (!offeredParents.containsKey(parentId)) continue;
                // v2 compatibility: explicit rejected rows are equivalent to omission in v3.
                if (value.has("accepted")
                        && (!value.path("accepted").isBoolean() || !value.path("accepted").asBoolean())) {
                    continue;
                }
                var requirementIds = tolerantRequirementIds(
                        value.path("requirementIds"), offeredRequirementIds);
                if (requirementIds.isEmpty() && offeredRequirementIds.size() == 1) {
                    requirementIds = Set.copyOf(offeredRequirementIds);
                }
                if (requirementIds.isEmpty()) continue;
                accepted.merge(parentId, requirementIds, this::union);
            }
            var result = new ArrayList<BatchedParentSelection>(parents.size());
            for (var parent : parents) {
                var requirementIds = accepted.getOrDefault(parent.parentChunkId(), Set.of());
                result.add(new BatchedParentSelection(
                        parent.parentChunkId(), !requirementIds.isEmpty(), requirementIds));
            }
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Goal 批量父块 Deep Read 输出不合法", failure);
        }
    }

    Map<String, Object> batchInput(
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            Set<UUID> targetRequirementIds,
            List<SearchQuery> queries,
            List<ParentContext> parents
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
        input.put("parents", parents.stream().map(this::batchParent).toList());
        return input;
    }

    private Map<String, Object> batchParent(ParentContext parent) {
        var value = new LinkedHashMap<String, Object>();
        value.put("parentChunkId", parent.parentChunkId());
        value.put("titlePath", parent.titlePath());
        value.put("retrievalScore", parent.retrievalScore());
        value.put("parentText", parent.text());
        value.put("matchedChildren", parent.childAnchors().stream().map(anchor -> {
            var child = new LinkedHashMap<String, Object>();
            child.put("childChunkId", anchor.childChunkId());
            child.put("start", anchor.parentLocalStart());
            child.put("end", anchor.parentLocalEnd());
            child.put("text", parent.text().substring(anchor.parentLocalStart(), anchor.parentLocalEnd()));
            child.put("queryMatches", parent.queryProvenance().stream()
                    .filter(provenance -> anchor.childChunkId().equals(provenance.childChunkId()))
                    .map(provenance -> Map.of("queryId", provenance.queryId(),
                            "retrievalScore", provenance.retrievalScore())).toList());
            return Map.copyOf(child);
        }).toList());
        return Map.copyOf(value);
    }

    private Set<UUID> strictRequirementIds(JsonNode value, Set<UUID> offered) {
        if (!value.isArray()) throw new IllegalStateException("requirementIds 必须是数组");
        var result = new LinkedHashSet<UUID>();
        value.forEach(item -> {
            var id = UUID.fromString(item.asText());
            if (!offered.contains(id) || !result.add(id)) {
                throw new IllegalStateException("requirementIds 包含未知或重复 ID");
            }
        });
        return Set.copyOf(result);
    }

    private Set<UUID> tolerantRequirementIds(JsonNode value, Set<UUID> offered) {
        if (!value.isArray()) return Set.of();
        var result = new LinkedHashSet<UUID>();
        value.forEach(item -> {
            try {
                var id = UUID.fromString(item.asText());
                if (offered.contains(id)) result.add(id);
            } catch (IllegalArgumentException ignored) {
                // A malformed entry must not discard other valid parent decisions in the same batch.
            }
        });
        return Set.copyOf(result);
    }

    private Map<String, Object> input(
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            Set<UUID> targetRequirementIds,
            List<SearchQuery> queries,
            ParentContext parent,
            Map<String, Object> parentContent
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
        var parentInput = new LinkedHashMap<String, Object>();
        parentInput.put("parentChunkId", parent.parentChunkId());
        parentInput.put("titlePath", parent.titlePath());
        parentInput.putAll(parentContent);
        input.put("parent", parentInput);
        return input;
    }

    private List<SourceBlock> sourceBlocks(ParentContext parent) {
        var result = new ArrayList<SourceBlock>();
        for (var segment : parent.sourceMap().segments()) {
            if (segment.chunkLocalEnd() > parent.text().length()) continue;
            result.add(new SourceBlock("B" + (segment.segmentOrder() + 1), segment,
                    parent.text().substring(segment.chunkLocalStart(), segment.chunkLocalEnd())));
        }
        return List.copyOf(result);
    }

    private Set<UUID> validRequirementIds(JsonNode value, Set<UUID> offered) {
        if (!value.isArray()) throw new IllegalStateException("requirementIds 必须是数组");
        var result = new LinkedHashSet<UUID>();
        value.forEach(item -> {
            try {
                var id = UUID.fromString(item.asText());
                if (offered.contains(id)) result.add(id);
            } catch (IllegalArgumentException ignored) {
                // Invalid or stale IDs do not invalidate other evidence in the same response.
            }
        });
        return Set.copyOf(result);
    }

    private void ensureInputBudget(String prompt, Map<String, Object> input, AgenticV5Limits limits) {
        try {
            int tokens = AgenticV4ModelInvoker.estimatedTokens(prompt)
                    + AgenticV4ModelInvoker.estimatedTokens(objectMapper.writeValueAsString(input));
            if (tokens > limits.tokens().deepReadInput()) {
                throw new IllegalStateException("Parent Deep Read 输入超过 Token 上限");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Parent Deep Read 输入无法序列化", failure);
        }
    }

    private String actionKey(ResearchPhase phase, UUID goalId, String strategy, UUID parentId) {
        return "deep-read:" + phase + ":" + goalId + ":" + strategy + ":" + parentId;
    }

    private Set<UUID> union(Set<UUID> left, Set<UUID> right) {
        var result = new LinkedHashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    private static String resource(String path) {
        try (var input = ParentDeepReadReasonerV8.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("缺少 Prompt: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt 无法读取: " + path, exception);
        }
    }

    record SourceBlock(String id, ChunkSourceSegment segment, String text) {
    }

    public record AdaptiveSelection(CandidateSpan span, Set<UUID> requirementIds) {
        public AdaptiveSelection {
            requirementIds = Set.copyOf(requirementIds);
        }
    }

    public record ParentSelection(boolean accepted, Set<UUID> requirementIds) {
        public ParentSelection {
            requirementIds = Set.copyOf(requirementIds);
        }
    }

    public record BatchedParentSelection(
            UUID parentChunkId,
            boolean accepted,
            Set<UUID> requirementIds
    ) {
        public BatchedParentSelection {
            requirementIds = Set.copyOf(requirementIds);
        }
    }
}
