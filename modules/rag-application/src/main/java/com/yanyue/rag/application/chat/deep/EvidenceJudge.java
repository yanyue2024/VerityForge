package com.yanyue.rag.application.chat.deep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.deep.AcceptedEvidence;
import com.yanyue.rag.domain.agent.budget.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.budget.BudgetDimension;
import com.yanyue.rag.domain.agent.deep.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.SearchMode;
import com.yanyue.rag.domain.agent.deep.DeepRagLimits;
import com.yanyue.rag.domain.agent.deep.GoalEvidencePool;
import com.yanyue.rag.domain.agent.deep.GoalPlan;
import com.yanyue.rag.domain.agent.deep.GoalStatus;
import com.yanyue.rag.domain.agent.deep.QueryPair;
import com.yanyue.rag.domain.agent.deep.RequestAnalysis;
import com.yanyue.rag.domain.agent.deep.RequirementStatus;
import com.yanyue.rag.domain.agent.deep.SearchQuery;
import com.yanyue.rag.domain.agent.deep.SearchQueryRole;
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
public class EvidenceJudge {
    private final DeepModelInvoker invoker;
    private final ObjectMapper objectMapper;
    private final String prompt = resource("prompts/deep-evidence-judge.md");

    public EvidenceJudge(DeepModelInvoker invoker, ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.objectMapper = objectMapper;
    }

    public JudgeDecision judge(
            UUID profileId,
            UUID runId,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            AgentBudgetLedger ledger,
            DeepRagLimits limits
    ) {
        return judge(profileId, runId, analysis, pool, ledger, limits, "evidence-judge");
    }

    public JudgeDecision judgeGoal(
            UUID profileId,
            UUID runId,
            String objective,
            GoalPlan goal,
            GoalEvidencePool pool,
            AgentBudgetLedger ledger,
            DeepRagLimits limits
    ) {
        var objectiveRequirement = new ObjectiveRequirement(
                UUID.nameUUIDFromBytes((runId + ":judge:" + goal.id())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                goal.question(), true, Set.of(goal.id()));
        var analysis = new RequestAnalysis(objective, List.of(objectiveRequirement), List.of(), List.of(goal));
        return judge(profileId, runId, analysis, pool, ledger, limits, "evidence-judge:" + goal.id());
    }

    private JudgeDecision judge(
            UUID profileId,
            UUID runId,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            AgentBudgetLedger ledger,
            DeepRagLimits limits,
            String actionKey
    ) {
        var evidence = boundedEvidence(analysis, pool, limits.tokens().judgeInput());
        var input = input(analysis, evidence, pool);
        try {
            int outputLimit = actionKey.startsWith("evidence-judge:")
                    ? Math.min(900, limits.tokens().judgeOutput())
                    : limits.tokens().judgeOutput();
            return invoker.invokeJson(profileId, runId, actionKey, "deep-evidence-judge-v1",
                    prompt, objectMapper.writeValueAsString(input), outputLimit,
                    BudgetDimension.EVIDENCE_JUDGE_CALL, ledger,
                    raw -> parse(raw, analysis, pool, evidence));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            if (isBudgetOrDeadline(failure)) throw failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException(failure);
            return deterministicFallback(analysis);
        }
    }

    JudgeDecision parse(
            String raw,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            Map<UUID, List<AcceptedEvidence>> offered
    ) {
        try {
            var root = objectMapper.readTree(raw);
            var nodes = array(root, "goalDecisions");
            if (nodes.size() != analysis.goals().size()) throw new IllegalStateException("Goal 决策数量不完整");
            var goals = analysis.goals().stream().collect(java.util.stream.Collectors.toMap(GoalPlan::id, value -> value));
            var seen = new HashSet<UUID>();
            var decisions = new ArrayList<GoalDecision>();
            for (var node : nodes) {
                var goalId = UUID.fromString(text(node, "goalId"));
                var goal = goals.get(goalId);
                if (goal == null || !seen.add(goalId)) throw new IllegalStateException("Goal 决策非法");
                decisions.add(parseGoal(node, goal, pool, offered.getOrDefault(goalId, List.of())));
            }
            return new JudgeDecision(decisions, false);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Deep RAG Evidence Judge 输出不合法", failure);
        }
    }

    private GoalDecision parseGoal(
            JsonNode node,
            GoalPlan goal,
            GoalEvidencePool pool,
            List<AcceptedEvidence> offered
    ) {
        var requirementNodes = array(node, "requirementDecisions");
        if (requirementNodes.size() != goal.requirements().size()) {
            throw new IllegalStateException("Requirement 决策数量不完整");
        }
        var offeredIds = offered.stream().map(AcceptedEvidence::evidenceId).collect(java.util.stream.Collectors.toSet());
        var seen = new HashSet<UUID>();
        var requirements = new ArrayList<RequirementDecision>();
        var missingIds = new LinkedHashSet<UUID>();
        for (var value : requirementNodes) {
            var id = UUID.fromString(text(value, "requirementId"));
            if (!goal.requirementIds().contains(id) || !seen.add(id)) throw new IllegalStateException("Requirement 决策非法");
            var status = RequirementStatus.valueOf(text(value, "status"));
            var evidenceIds = uuids(value.path("evidenceIds"));
            if (status == RequirementStatus.COVERED) {
                var allowed = pool.forRequirement(goal.id(), id).stream().map(AcceptedEvidence::evidenceId)
                        .collect(java.util.stream.Collectors.toSet());
                if (evidenceIds.isEmpty() || !allowed.containsAll(evidenceIds) || !offeredIds.containsAll(evidenceIds)) {
                    throw new IllegalStateException("COVERED 引用了未提供或非法 Evidence");
                }
            } else {
                if (!evidenceIds.isEmpty()) throw new IllegalStateException("MISSING 不得引用 Evidence");
                missingIds.add(id);
            }
            requirements.add(new RequirementDecision(id, status, evidenceIds));
        }
        var repairPair = missingIds.isEmpty() ? null : parseRepairPair(node, goal, missingIds);
        var status = missingIds.isEmpty() ? GoalStatus.SATISFIED_LOCKED : GoalStatus.NEEDS_REPAIR;
        return new GoalDecision(goal.id(), requirements, repairPair, status);
    }

    private QueryPair parseRepairPair(JsonNode node, GoalPlan goal, Set<UUID> missingIds) {
        var nodes = array(node, "repairQueries");
        if (nodes.size() != 2) throw new IllegalStateException("未完成 Goal 必须有两个补检 Query");
        SearchQuery keyword = null;
        SearchQuery semantic = null;
        for (var value : nodes) {
            var role = SearchQueryRole.valueOf(text(value, "role"));
            var mode = SearchMode.valueOf(text(value, "searchMode"));
            var targets = uuids(value.path("targetRequirementIds"));
            if (!targets.equals(missingIds)) throw new IllegalStateException("补检 Query 必须共同覆盖全部缺失面");
            var queryText = text(value, "text");
            if (role == SearchQueryRole.REPAIR_KEYWORD) {
                queryText = KeywordQueryPolicy.normalize(queryText, goal.question());
            }
            var query = new SearchQuery(UUID.randomUUID(), goal.id(), ResearchPhase.REPAIR, role,
                    queryText, mode, targets);
            if (role == SearchQueryRole.REPAIR_KEYWORD) keyword = query;
            if (role == SearchQueryRole.REPAIR_SEMANTIC) semantic = query;
        }
        return new QueryPair(goal.id(), ResearchPhase.REPAIR, keyword, semantic);
    }

    private Map<UUID, List<AcceptedEvidence>> boundedEvidence(
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            int maximumTokens
    ) {
        var result = new LinkedHashMap<UUID, List<AcceptedEvidence>>();
        int remaining = Math.max(1, maximumTokens - DeepModelInvoker.estimatedTokens(prompt) - 700);
        for (var goal : analysis.goals()) {
            var selected = new ArrayList<AcceptedEvidence>();
            for (var evidence : pool.forGoal(goal.id()).stream()
                    .sorted(java.util.Comparator.comparingDouble(AcceptedEvidence::retrievalScore).reversed()).toList()) {
                int tokens = DeepModelInvoker.estimatedTokens(judgeQuote(pool, evidence)) + 40;
                if (tokens > remaining) continue;
                selected.add(evidence);
                remaining -= tokens;
            }
            result.put(goal.id(), List.copyOf(selected));
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> input(
            RequestAnalysis analysis,
            Map<UUID, List<AcceptedEvidence>> evidence,
            GoalEvidencePool pool
    ) {
        return Map.of("objective", analysis.standaloneObjective(), "goals", analysis.goals().stream().map(goal -> Map.of(
                "goalId", goal.id(), "question", goal.question(),
                "requirements", goal.requirements().stream().map(value -> Map.of(
                        "requirementId", value.id(), "description", value.description())).toList(),
                "evidence", evidence.getOrDefault(goal.id(), List.of()).stream().map(value -> Map.of(
                        "evidenceId", value.evidenceId(), "requirementIds", value.activeRequirementIds(),
                        "quote", judgeQuote(pool, value), "titlePath", value.titlePath())).toList())).toList());
    }

    private String judgeQuote(GoalEvidencePool pool, AcceptedEvidence evidence) {
        var supports = pool.supportQuotes(evidence.evidenceId());
        return supports.isEmpty() ? evidence.quote() : String.join("\n", supports);
    }

    private JudgeDecision deterministicFallback(RequestAnalysis analysis) {
        var decisions = analysis.goals().stream().map(goal -> {
            var requirementIds = goal.requirementIds();
            var requirements = goal.requirements().stream().map(value -> new RequirementDecision(
                    value.id(), RequirementStatus.MISSING, Set.<UUID>of())).toList();
            var keywordInput = limit(goal.question() + " " + goal.requirements().stream()
                    .map(value -> value.description()).collect(java.util.stream.Collectors.joining(" ")));
            var keyword = KeywordQueryPolicy.normalize(keywordInput, goal.question());
            var semantic = limit("查找能够直接回答以下问题缺失证据面的资料：" + goal.question());
            if (normalize(keyword).equals(normalize(semantic))) semantic = limit("完整说明：" + semantic);
            var pair = new QueryPair(goal.id(), ResearchPhase.REPAIR,
                    new SearchQuery(UUID.randomUUID(), goal.id(), ResearchPhase.REPAIR,
                            SearchQueryRole.REPAIR_KEYWORD, keyword, SearchMode.KEYWORD, requirementIds),
                    new SearchQuery(UUID.randomUUID(), goal.id(), ResearchPhase.REPAIR,
                            SearchQueryRole.REPAIR_SEMANTIC, semantic, SearchMode.SEMANTIC, requirementIds));
            return new GoalDecision(goal.id(), requirements, pair, GoalStatus.NEEDS_REPAIR);
        }).toList();
        return new JudgeDecision(decisions, true);
    }

    private boolean isBudgetOrDeadline(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            var message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("budget") || message.contains("预算") || message.contains("deadline")
                    || message.contains("timeout") || message.contains("超时")) return true;
        }
        return false;
    }

    private String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
    }

    private String limit(String value) {
        var safe = value.strip();
        return safe.substring(0, Math.min(300, safe.length()));
    }

    private List<JsonNode> array(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isArray()) throw new IllegalStateException(field + " 必须是数组");
        var result = new ArrayList<JsonNode>();
        value.forEach(result::add);
        return result;
    }

    private Set<UUID> uuids(JsonNode value) {
        if (!value.isArray()) throw new IllegalStateException("字段必须是数组");
        var result = new LinkedHashSet<UUID>();
        value.forEach(item -> result.add(UUID.fromString(item.asText())));
        return Set.copyOf(result);
    }

    private String text(JsonNode node, String field) {
        var value = node.path(field).asText("").strip();
        if (value.isEmpty()) throw new IllegalStateException(field + " 不能为空");
        return value;
    }

    private static String resource(String path) {
        try (var input = EvidenceJudge.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("缺少 Prompt: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt 无法读取: " + path, exception);
        }
    }

    public record RequirementDecision(UUID requirementId, RequirementStatus status, Set<UUID> evidenceIds) {
        public RequirementDecision {
            evidenceIds = Set.copyOf(evidenceIds);
        }
    }

    public record GoalDecision(
            UUID goalId,
            List<RequirementDecision> requirements,
            QueryPair repairQueryPair,
            GoalStatus status
    ) {
        public GoalDecision {
            requirements = List.copyOf(requirements);
        }
    }

    public record JudgeDecision(List<GoalDecision> goals, boolean degraded) {
        public JudgeDecision {
            goals = List.copyOf(goals);
        }
    }
}
