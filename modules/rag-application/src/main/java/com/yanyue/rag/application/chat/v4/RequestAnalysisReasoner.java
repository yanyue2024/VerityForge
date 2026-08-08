package com.yanyue.rag.application.chat.v4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.AnswerConstraint;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.GoalPlan;
import com.yanyue.rag.domain.agent.v4.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.v4.RequestAnalysis;
import com.yanyue.rag.domain.agent.v4.RequirementPlan;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v4.SearchQuery;
import com.yanyue.rag.domain.agent.v4.SearchQueryRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RequestAnalysisReasoner {
    private final AgenticV4ModelInvoker invoker;
    private final ObjectMapper objectMapper;
    private final String prompt = resource("prompts/agentic-v4-request-analysis.md");

    public RequestAnalysisReasoner(AgenticV4ModelInvoker invoker, ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.objectMapper = objectMapper;
    }

    public RequestAnalysis analyze(
            UUID profileId,
            UUID runId,
            String originalQuestion,
            List<String> recentMessages,
            AgentBudgetLedger ledger
    ) {
        try {
            var input = boundedInput(originalQuestion, recentMessages);
            return invoker.invokeJson(profileId, runId, "request-analysis", "agentic-v4-request-analysis",
                    prompt, objectMapper.writeValueAsString(input), 1_800,
                    BudgetDimension.REQUEST_ANALYSIS_CALL, ledger, this::parse);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            if (failure instanceof RuntimeException runtime && isBudgetOrDeadline(runtime)) throw runtime;
            return fallback(originalQuestion);
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

    private Map<String, Object> boundedInput(String originalQuestion, List<String> recentMessages) {
        var boundedQuestion = truncateToTokens(originalQuestion, 2_200);
        var boundedMessages = new ArrayList<>(boundedRecentMessages(recentMessages, 1_200));
        var input = analysisInput(boundedQuestion, boundedMessages);
        while (requestTokens(input) > 4_000 && !boundedMessages.isEmpty()) {
            boundedMessages.removeFirst();
            input = analysisInput(boundedQuestion, boundedMessages);
        }
        if (requestTokens(input) > 4_000) {
            int overflow = requestTokens(input) - 4_000;
            int questionTokens = AgenticV4ModelInvoker.estimatedTokens(boundedQuestion);
            boundedQuestion = truncateToTokens(boundedQuestion, Math.max(1, questionTokens - overflow - 16));
            input = analysisInput(boundedQuestion, boundedMessages);
        }
        if (requestTokens(input) > 4_000) {
            throw new IllegalStateException("Request Analysis 元数据超过单次输入 Token 上限");
        }
        return input;
    }

    private Map<String, Object> analysisInput(String question, List<String> recentMessages) {
        var input = new LinkedHashMap<String, Object>();
        input.put("originalQuestion", question);
        input.put("recentMessages", List.copyOf(recentMessages));
        input.put("maximumGoals", 3);
        input.put("maximumRequirementsPerGoal", 3);
        return input;
    }

    private int requestTokens(Map<String, Object> input) {
        return AgenticV4ModelInvoker.estimatedTokens(prompt)
                + AgenticV4ModelInvoker.estimatedTokens(objectMapper.valueToTree(input).toString());
    }

    RequestAnalysis parse(String raw) {
        try {
            var root = objectMapper.readTree(raw);
            var goalIds = new LinkedHashMap<String, UUID>();
            var requirementIds = new LinkedHashMap<String, Map<String, UUID>>();
            var goalsNode = requiredArray(root, "goals");
            for (var goal : goalsNode) {
                var key = requiredText(goal, "key");
                if (goalIds.putIfAbsent(key, UUID.randomUUID()) != null) {
                    throw new IllegalStateException("Goal key 重复");
                }
                var ids = new LinkedHashMap<String, UUID>();
                for (var requirement : requiredArray(goal, "requirements")) {
                    if (ids.putIfAbsent(requiredText(requirement, "key"), UUID.randomUUID()) != null) {
                        throw new IllegalStateException("Requirement key 重复");
                    }
                }
                requirementIds.put(key, ids);
            }

            var goals = new ArrayList<GoalPlan>();
            for (var goal : goalsNode) {
                var key = requiredText(goal, "key");
                var goalId = goalIds.get(key);
                var requirements = new ArrayList<RequirementPlan>();
                for (var requirement : requiredArray(goal, "requirements")) {
                    var requirementKey = requiredText(requirement, "key");
                    requirements.add(new RequirementPlan(requirementIds.get(key).get(requirementKey), goalId,
                            requiredText(requirement, "description")));
                }
                var queryNode = goal.path("initialQuery");
                var mode = SearchMode.valueOf(requiredText(queryNode, "searchMode"));
                var query = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                        SearchQueryRole.INITIAL, requiredText(queryNode, "text"), mode,
                        new LinkedHashSet<>(requirementIds.get(key).values()));
                goals.add(new GoalPlan(goalId, requiredText(goal, "question"), requirements, query));
            }

            var objectives = new ArrayList<ObjectiveRequirement>();
            for (var value : requiredArray(root, "objectiveRequirements")) {
                var mapped = new LinkedHashSet<UUID>();
                for (var key : strings(value.path("mappedGoalKeys"))) {
                    var id = goalIds.get(key);
                    if (id == null) throw new IllegalStateException("ObjectiveRequirement 引用了未知 Goal");
                    mapped.add(id);
                }
                objectives.add(new ObjectiveRequirement(UUID.randomUUID(), requiredText(value, "description"),
                        value.path("mandatory").asBoolean(true), mapped));
            }
            var constraints = new ArrayList<AnswerConstraint>();
            for (var value : optionalArray(root, "answerConstraints")) {
                var applies = new LinkedHashSet<UUID>();
                for (var key : strings(value.path("appliesToGoalKeys"))) {
                    var id = goalIds.get(key);
                    if (id == null) throw new IllegalStateException("AnswerConstraint 引用了未知 Goal");
                    applies.add(id);
                }
                constraints.add(new AnswerConstraint(requiredText(value, "description"), applies));
            }
            return new RequestAnalysis(requiredText(root, "standaloneObjective"), objectives, constraints, goals);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Request Analysis 输出不合法", failure);
        }
    }

    private RequestAnalysis fallback(String question) {
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var requirement = new RequirementPlan(requirementId, goalId, "回答该问题所需的直接事实依据");
        var mode = question.matches(".*([A-Za-z]+[-_.]?\\d|\\d+[._-]\\d+|错误码|编号|版本).*?")
                ? SearchMode.KEYWORD : SearchMode.SEMANTIC;
        var query = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY, SearchQueryRole.INITIAL,
                question.substring(0, Math.min(300, question.length())), mode, java.util.Set.of(requirementId));
        var boundedQuestion = question.substring(0, Math.min(1_000, question.length()));
        var goal = new GoalPlan(goalId, boundedQuestion, List.of(requirement), query);
        return new RequestAnalysis(question.substring(0, Math.min(2_000, question.length())),
                List.of(new ObjectiveRequirement(UUID.randomUUID(), "回答用户问题", true, java.util.Set.of(goalId))),
                List.of(), List.of(goal));
    }

    private List<String> boundedRecentMessages(List<String> messages, int maximumTokens) {
        if (messages == null || messages.isEmpty()) return List.of();
        var result = new ArrayList<String>();
        int remaining = maximumTokens;
        for (var message : messages.stream().limit(6).toList()) {
            if (remaining <= 0) break;
            var bounded = truncateToTokens(message, remaining);
            result.add(bounded);
            remaining -= AgenticV4ModelInvoker.estimatedTokens(bounded);
        }
        return List.copyOf(result);
    }

    private String truncateToTokens(String value, int maximumTokens) {
        if (value == null || value.isBlank()) return "";
        if (AgenticV4ModelInvoker.estimatedTokens(value) <= maximumTokens) return value;
        int low = 1;
        int high = value.length();
        int best = 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int tokens = AgenticV4ModelInvoker.estimatedTokens(value.substring(0, middle));
            if (tokens <= maximumTokens) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return value.substring(0, best);
    }

    private List<JsonNode> requiredArray(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isArray() || value.isEmpty()) throw new IllegalStateException(field + " 必须是非空数组");
        var result = new ArrayList<JsonNode>();
        value.forEach(result::add);
        return result;
    }

    private List<JsonNode> optionalArray(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray()) throw new IllegalStateException(field + " 必须是数组");
        var result = new ArrayList<JsonNode>();
        value.forEach(result::add);
        return result;
    }

    private List<String> strings(JsonNode value) {
        if (!value.isArray()) throw new IllegalStateException("字段必须是数组");
        var result = new ArrayList<String>();
        value.forEach(item -> result.add(item.asText()));
        return result;
    }

    private String requiredText(JsonNode node, String field) {
        var value = node.path(field).asText("").strip();
        if (value.isEmpty()) throw new IllegalStateException(field + " 不能为空");
        return value;
    }

    private static String resource(String path) {
        try (var input = RequestAnalysisReasoner.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("缺少 Prompt: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt 无法读取: " + path, exception);
        }
    }
}
