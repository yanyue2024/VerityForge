package com.yanyue.rag.application.chat.v4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v4.GoalPlan;
import com.yanyue.rag.domain.agent.v4.GoalStatus;
import com.yanyue.rag.domain.agent.v4.RepairCompletionMode;
import com.yanyue.rag.domain.agent.v4.RepairTarget;
import com.yanyue.rag.domain.agent.v4.RequirementStatus;
import com.yanyue.rag.domain.agent.v4.RequestAnalysis;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v4.SearchQuery;
import com.yanyue.rag.domain.agent.v4.SearchQueryRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class EvidenceJudgeReasoner {
    private final AgenticV4ModelInvoker invoker;
    private final ObjectMapper objectMapper;
    private final String prompt = resource("prompts/agentic-v4-evidence-judge.md");

    public EvidenceJudgeReasoner(AgenticV4ModelInvoker invoker, ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.objectMapper = objectMapper;
    }

    public JudgeDecision judge(
            UUID profileId,
            UUID runId,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
        AgentBudgetLedger ledger
    ) {
        try {
            var input = input(analysis, pool);
            return invoker.invokeJson(profileId, runId, "evidence-judge", "agentic-v4-evidence-judge",
                    prompt, objectMapper.writeValueAsString(input.payload()), 1_800,
                    BudgetDimension.EVIDENCE_JUDGE_CALL, ledger,
                    raw -> parse(raw, analysis, pool, input.offeredEvidenceIds()));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            if (failure instanceof RuntimeException runtime && isBudgetOrDeadline(runtime)) throw runtime;
            return deterministicFallback(analysis);
        }
    }

    private boolean isBudgetOrDeadline(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            var message = current.getMessage() == null ? ""
                    : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("budget exhausted") || message.contains("budget infeasible")
                    || message.contains("预算不足") || message.contains("deadline") || message.contains("超时")
                    || message.contains("timeout")) return true;
        }
        return false;
    }

    private JudgeInputPack input(RequestAnalysis analysis, GoalEvidencePool pool) {
        var selectedByGoal = new LinkedHashMap<UUID, List<AcceptedEvidence>>();
        selectEvidence(analysis, pool, 6_500).forEach(
                (goalId, values) -> selectedByGoal.put(goalId, new ArrayList<>(values)));
        var result = judgePayload(analysis, pool, selectedByGoal);
        while (AgenticV4ModelInvoker.estimatedTokens(prompt)
                + AgenticV4ModelInvoker.estimatedTokens(objectMapper.valueToTree(result).toString()) > 8_000) {
            if (!removeLowestPriorityEvidence(selectedByGoal)) {
                throw new IllegalStateException("Evidence Judge 元数据超过单次输入 Token 上限");
            }
            result = judgePayload(analysis, pool, selectedByGoal);
        }
        var offered = selectedByGoal.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().stream().map(AcceptedEvidence::evidenceId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())));
        return new JudgeInputPack(result, offered);
    }

    private Map<String, Object> judgePayload(
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            Map<UUID, List<AcceptedEvidence>> selectedByGoal
    ) {
        var result = new LinkedHashMap<String, Object>();
        result.put("standaloneObjective", analysis.standaloneObjective());
        result.put("objectiveRequirements", analysis.objectiveRequirements());
        result.put("answerConstraints", analysis.answerConstraints());
        result.put("goals", analysis.goals().stream().map(goal -> Map.of(
                "goalId", goal.id(), "question", goal.question(), "requirements", goal.requirements(),
                "acceptedEvidence", selectedByGoal.getOrDefault(goal.id(), List.of()).stream()
                        .map(this::evidenceInput).toList(),
                "omittedEvidenceCount", Math.max(0, pool.forGoal(goal.id()).size()
                        - selectedByGoal.getOrDefault(goal.id(), List.of()).size()))).toList());
        return result;
    }

    private boolean removeLowestPriorityEvidence(Map<UUID, List<AcceptedEvidence>> selectedByGoal) {
        for (var values : selectedByGoal.values().stream().filter(value -> value.size() > 1).toList()) {
            values.removeLast();
            return true;
        }
        for (var values : selectedByGoal.values()) {
            if (!values.isEmpty()) {
                values.removeLast();
                return true;
            }
        }
        return false;
    }

    private Map<UUID, List<AcceptedEvidence>> selectEvidence(
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            int maximumTokens
    ) {
        var selected = new LinkedHashMap<UUID, LinkedHashMap<UUID, AcceptedEvidence>>();
        int remaining = maximumTokens;
        for (int pass = 0; pass < 2; pass++) {
            for (var goal : analysis.goals()) {
                var goalSelection = selected.computeIfAbsent(goal.id(), ignored -> new LinkedHashMap<>());
                for (var requirement : goal.requirements()) {
                    var candidates = pool.forRequirement(goal.id(), requirement.id()).stream()
                            .filter(value -> !goalSelection.containsKey(value.evidenceId()))
                            .sorted(java.util.Comparator.comparingDouble(AcceptedEvidence::retrievalScore).reversed())
                            .toList();
                    if (candidates.isEmpty()) continue;
                    var candidate = candidates.getFirst();
                    int tokens = AgenticV4ModelInvoker.estimatedTokens(candidate.quote())
                            + AgenticV4ModelInvoker.estimatedTokens(candidate.titlePath()) + 20;
                    if (tokens > remaining) continue;
                    goalSelection.put(candidate.evidenceId(), candidate);
                    remaining -= tokens;
                }
            }
        }
        var result = new LinkedHashMap<UUID, List<AcceptedEvidence>>();
        selected.forEach((goalId, values) -> result.put(goalId, List.copyOf(values.values())));
        return Map.copyOf(result);
    }

    private Map<String, Object> evidenceInput(AcceptedEvidence evidence) {
        return Map.of("evidenceId", evidence.evidenceId(), "quote", evidence.quote(),
                "requirementIds", evidence.activeRequirementIds(), "titlePath", evidence.titlePath());
    }

    JudgeDecision parse(String raw, RequestAnalysis analysis, GoalEvidencePool pool) {
        var offered = analysis.goals().stream().collect(java.util.stream.Collectors.toMap(
                GoalPlan::id, goal -> pool.forGoal(goal.id()).stream().map(AcceptedEvidence::evidenceId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())));
        return parse(raw, analysis, pool, offered);
    }

    JudgeDecision parse(
            String raw,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            Map<UUID, Set<UUID>> offeredEvidenceIds
    ) {
        try {
            var root = objectMapper.readTree(raw);
            var values = array(root, "goalDecisions");
            if (values.size() != analysis.goals().size()) throw new IllegalStateException("Goal 决策数量不完整");
            var knownGoals = analysis.goals().stream().collect(java.util.stream.Collectors.toMap(
                    GoalPlan::id, value -> value));
            var seenGoals = new HashSet<UUID>();
            var decisions = new ArrayList<GoalDecision>();
            for (var value : values) {
                var goalId = UUID.fromString(text(value, "goalId"));
                var goal = knownGoals.get(goalId);
                if (goal == null || !seenGoals.add(goalId)) throw new IllegalStateException("Goal 决策非法");
                decisions.add(parseGoal(value, goal, pool,
                        offeredEvidenceIds.getOrDefault(goalId, Set.of())));
            }
            return new JudgeDecision(decisions, false);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Evidence Judge 输出不合法", failure);
        }
    }

    private GoalDecision parseGoal(
            JsonNode node,
            GoalPlan goal,
            GoalEvidencePool pool,
            Set<UUID> offeredEvidenceIds
    ) {
        var requirementNodes = array(node, "requirementDecisions");
        if (requirementNodes.size() != goal.requirements().size()) {
            throw new IllegalStateException("Requirement 决策数量不完整");
        }
        var seen = new HashSet<UUID>();
        var requirements = new ArrayList<RequirementDecision>();
        var targetByKey = new LinkedHashMap<String, RepairTarget>();
        for (var value : requirementNodes) {
            var requirementId = UUID.fromString(text(value, "requirementId"));
            if (!goal.requirementIds().contains(requirementId) || !seen.add(requirementId)) {
                throw new IllegalStateException("Requirement 决策非法");
            }
            var status = RequirementStatus.valueOf(text(value, "status"));
            if (status == RequirementStatus.UNASSESSED || status == RequirementStatus.NOT_FOUND_WITHIN_BUDGET) {
                throw new IllegalStateException("Judge 返回了服务端内部状态");
            }
            var evidenceIds = uuids(value.path("evidenceIds"));
            if (status == RequirementStatus.COVERED) {
                validateCovered(goal.id(), requirementId, evidenceIds, pool, offeredEvidenceIds);
            }
            if (status == RequirementStatus.CONFLICTING) {
                validateConflicting(goal.id(), requirementId, evidenceIds, pool, offeredEvidenceIds);
            }
            if (status == RequirementStatus.MISSING && !evidenceIds.isEmpty()) {
                throw new IllegalStateException("MISSING 不得引用 Evidence");
            }
            RepairTarget target = null;
            if (status == RequirementStatus.MISSING || status == RequirementStatus.CONFLICTING) {
                var targetNode = value.path("repairTarget");
                var key = text(targetNode, "key");
                var completionMode = status == RequirementStatus.CONFLICTING
                        ? RepairCompletionMode.REVIEW_REQUIRED
                        : RepairCompletionMode.valueOf(text(targetNode, "completionMode"));
                target = RepairTarget.open(UUID.randomUUID(), goal.id(), requirementId,
                        text(targetNode, "description"), completionMode);
                if (targetByKey.putIfAbsent(key, target) != null) throw new IllegalStateException("Target key 重复");
            }
            requirements.add(new RequirementDecision(requirementId, status, evidenceIds, target));
        }
        var statusByRequirement = requirements.stream().collect(java.util.stream.Collectors.toMap(
                RequirementDecision::requirementId, RequirementDecision::status));
        var conflicts = new ArrayList<ConflictDecision>();
        var conflictRequirements = new HashSet<UUID>();
        for (var conflictNode : array(node, "conflicts")) {
            var requirementId = UUID.fromString(text(conflictNode, "requirementId"));
            var evidenceIds = uuids(conflictNode.path("evidenceIds"));
            if (statusByRequirement.get(requirementId) != RequirementStatus.CONFLICTING
                    || !conflictRequirements.add(requirementId)) {
                throw new IllegalStateException("Conflict 没有对应唯一的 CONFLICTING Requirement");
            }
            validateConflicting(goal.id(), requirementId, evidenceIds, pool, offeredEvidenceIds);
            conflicts.add(new ConflictDecision(requirementId, evidenceIds));
        }
        long conflictingRequirements = requirements.stream()
                .filter(value -> value.status() == RequirementStatus.CONFLICTING).count();
        if (conflicts.size() != conflictingRequirements) {
            throw new IllegalStateException("CONFLICTING Requirement 必须有冲突证据组");
        }
        var targets = targetByKey.values().stream().toList();
        var queries = targets.isEmpty() ? List.<SearchQuery>of() : parseQueries(node, goal, targetByKey);
        var goalStatus = requirements.stream().allMatch(value -> value.status() == RequirementStatus.COVERED)
                ? GoalStatus.SATISFIED_LOCKED
                : requirements.stream().anyMatch(value -> value.status() == RequirementStatus.CONFLICTING)
                        ? GoalStatus.CONFLICTED : GoalStatus.NEEDS_REPAIR;
        return new GoalDecision(goal.id(), requirements, targets, queries, conflicts, goalStatus);
    }

    private List<SearchQuery> parseQueries(JsonNode node, GoalPlan goal, Map<String, RepairTarget> targetByKey) {
        var values = array(node, "repairQueries");
        if (values.size() != 2) throw new IllegalStateException("未完成 Goal 必须有两个补检 Query");
        var result = new ArrayList<SearchQuery>();
        var coveredTargets = new HashSet<UUID>();
        for (var value : values) {
            var role = SearchQueryRole.valueOf(text(value, "role"));
            var mode = SearchMode.valueOf(text(value, "searchMode"));
            var requirements = uuids(value.path("targetRequirementIds"));
            if (!goal.requirementIds().containsAll(requirements) || requirements.isEmpty()) {
                throw new IllegalStateException("补检 Query 目标 Requirement 非法");
            }
            for (var key : strings(value.path("repairTargetKeys"))) {
                var target = targetByKey.get(key);
                if (target == null || !requirements.contains(target.requirementId())) {
                    throw new IllegalStateException("补检 Query 目标 Target 非法");
                }
                coveredTargets.add(target.id());
            }
            result.add(new SearchQuery(UUID.randomUUID(), goal.id(), ResearchPhase.REPAIR, role,
                    text(value, "text"), mode, requirements));
        }
        if (result.stream().map(SearchQuery::role).collect(java.util.stream.Collectors.toSet()).size() != 2
                || coveredTargets.size() != targetByKey.size()
                || normalize(result.get(0).text()).equals(normalize(result.get(1).text()))) {
            throw new IllegalStateException("补检 Query 没有完整覆盖或发生重复");
        }
        return List.copyOf(result);
    }

    private void validateCovered(
            UUID goalId,
            UUID requirementId,
            Set<UUID> ids,
            GoalEvidencePool pool,
            Set<UUID> offeredEvidenceIds
    ) {
        if (ids.isEmpty()) throw new IllegalStateException("COVERED 必须引用 Evidence");
        var allowed = pool.forRequirement(goalId, requirementId).stream().map(AcceptedEvidence::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(ids) || !offeredEvidenceIds.containsAll(ids)) {
            throw new IllegalStateException("COVERED 引用了未提供或非法 Evidence");
        }
    }

    private void validateConflicting(
            UUID goalId,
            UUID requirementId,
            Set<UUID> ids,
            GoalEvidencePool pool,
            Set<UUID> offeredEvidenceIds
    ) {
        if (ids.size() < 2) throw new IllegalStateException("CONFLICTING 必须引用至少两条 Evidence");
        var allowed = pool.forRequirement(goalId, requirementId).stream().map(AcceptedEvidence::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(ids) || !offeredEvidenceIds.containsAll(ids)) {
            throw new IllegalStateException("CONFLICTING 引用了未提供或非法 Evidence");
        }
    }

    private JudgeDecision deterministicFallback(RequestAnalysis analysis) {
        var decisions = analysis.goals().stream().map(goal -> {
            var targets = goal.requirements().stream().map(requirement -> RepairTarget.open(
                    UUID.randomUUID(), goal.id(), requirement.id(), requirement.description(),
                    RepairCompletionMode.REVIEW_REQUIRED)).toList();
            var requirementIds = goal.requirementIds();
            var keywordText = limit(goal.question() + " " + targets.stream()
                    .map(RepairTarget::description).collect(java.util.stream.Collectors.joining(" ")));
            var semanticText = limit("查找能够直接回答以下问题和证据面的资料：" + goal.question() + "；"
                    + targets.stream().map(RepairTarget::description).collect(java.util.stream.Collectors.joining("；")));
            var queries = List.of(
                    new SearchQuery(UUID.randomUUID(), goal.id(), ResearchPhase.REPAIR,
                            SearchQueryRole.REPAIR_KEYWORD, keywordText, SearchMode.KEYWORD, requirementIds),
                    new SearchQuery(UUID.randomUUID(), goal.id(), ResearchPhase.REPAIR,
                            SearchQueryRole.REPAIR_SEMANTIC, semanticText, SearchMode.SEMANTIC, requirementIds));
            var requirements = goal.requirements().stream().map(requirement -> new RequirementDecision(
                    requirement.id(), RequirementStatus.UNASSESSED, Set.of(), targets.stream()
                            .filter(target -> target.requirementId().equals(requirement.id())).findFirst().orElseThrow()))
                    .toList();
            return new GoalDecision(goal.id(), requirements, targets, queries, List.of(), GoalStatus.NEEDS_REPAIR);
        }).toList();
        return new JudgeDecision(decisions, true);
    }

    private List<JsonNode> array(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isArray()) throw new IllegalStateException(field + " 必须是数组");
        var result = new ArrayList<JsonNode>();
        value.forEach(result::add);
        return result;
    }

    private Set<UUID> uuids(JsonNode value) {
        var result = new LinkedHashSet<UUID>();
        if (!value.isArray()) throw new IllegalStateException("字段必须是数组");
        value.forEach(item -> result.add(UUID.fromString(item.asText())));
        return Set.copyOf(result);
    }

    private List<String> strings(JsonNode value) {
        if (!value.isArray()) throw new IllegalStateException("字段必须是数组");
        var result = new ArrayList<String>();
        value.forEach(item -> result.add(item.asText()));
        return result;
    }

    private String text(JsonNode node, String field) {
        var value = node.path(field).asText("").strip();
        if (value.isEmpty()) throw new IllegalStateException(field + " 不能为空");
        return value;
    }

    private String limit(String value) {
        return value.substring(0, Math.min(300, value.length()));
    }

    private String normalize(String value) {
        return value.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String resource(String path) {
        try (var input = EvidenceJudgeReasoner.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("缺少 Prompt: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt 无法读取: " + path, exception);
        }
    }

    public record JudgeDecision(List<GoalDecision> goals, boolean degraded) {
        public JudgeDecision { goals = List.copyOf(goals); }
    }

    public record GoalDecision(
            UUID goalId,
            List<RequirementDecision> requirements,
            List<RepairTarget> repairTargets,
            List<SearchQuery> repairQueries,
            List<ConflictDecision> conflicts,
            GoalStatus goalStatus
    ) {
        public GoalDecision {
            requirements = List.copyOf(requirements);
            repairTargets = List.copyOf(repairTargets);
            repairQueries = List.copyOf(repairQueries);
            conflicts = List.copyOf(conflicts);
        }

        public GoalDecision(
                UUID goalId,
                List<RequirementDecision> requirements,
                List<RepairTarget> repairTargets,
                List<SearchQuery> repairQueries,
                GoalStatus goalStatus
        ) {
            this(goalId, requirements, repairTargets, repairQueries, List.of(), goalStatus);
        }
    }

    public record ConflictDecision(UUID requirementId, Set<UUID> evidenceIds) {
        public ConflictDecision {
            evidenceIds = Set.copyOf(evidenceIds);
        }
    }

    public record RequirementDecision(
            UUID requirementId,
            RequirementStatus status,
            Set<UUID> evidenceIds,
            RepairTarget repairTarget
    ) {
        public RequirementDecision { evidenceIds = Set.copyOf(evidenceIds); }
    }

    private record JudgeInputPack(
            Map<String, Object> payload,
            Map<UUID, Set<UUID>> offeredEvidenceIds
    ) {
        private JudgeInputPack {
            payload = Map.copyOf(payload);
            offeredEvidenceIds = Map.copyOf(offeredEvidenceIds);
        }
    }
}
