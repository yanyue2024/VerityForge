package com.yanyue.rag.application.chat.deep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.budget.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.deep.AnswerConstraint;
import com.yanyue.rag.domain.agent.budget.BudgetDimension;
import com.yanyue.rag.domain.agent.deep.ObjectiveRequirement;
import com.yanyue.rag.domain.agent.deep.RequirementPlan;
import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.SearchMode;
import com.yanyue.rag.domain.agent.deep.DeepRagLimits;
import com.yanyue.rag.domain.agent.deep.GoalPlan;
import com.yanyue.rag.domain.agent.deep.QueryPair;
import com.yanyue.rag.domain.agent.deep.RequestAnalysis;
import com.yanyue.rag.domain.agent.deep.SearchQuery;
import com.yanyue.rag.domain.agent.deep.SearchQueryRole;
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
public class RequestAnalyzer {
    private final DeepModelInvoker invoker;
    private final ObjectMapper objectMapper;
    private final String prompt = resource("prompts/deep-request-analysis.md");

    public RequestAnalyzer(DeepModelInvoker invoker, ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.objectMapper = objectMapper;
    }

    public RequestAnalysis analyze(
            UUID profileId,
            UUID runId,
            String originalQuestion,
            List<String> recentMessages,
            AgentBudgetLedger ledger,
            DeepRagLimits limits
    ) {
        try {
            var input = boundedInput(originalQuestion, recentMessages, limits, prompt);
            var analysis = invoker.invokeJson(profileId, runId, "request-analysis", "deep-request-analysis-v1",
                    prompt, objectMapper.writeValueAsString(input), limits.tokens().requestAnalysisOutput(),
                    BudgetDimension.REQUEST_ANALYSIS_CALL, ledger, this::parseModelOutput);
            return applyOriginalQueryAnchors(analysis, originalQuestion);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            if (isBudgetOrDeadline(failure)) throw failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException(failure);
            return applyOriginalQueryAnchors(fallback(originalQuestion), originalQuestion);
        }
    }

    RequestAnalysis parse(String raw, String originalQuestion) {
        return applyOriginalQueryAnchors(parseModelOutput(raw), originalQuestion);
    }

    private RequestAnalysis parseModelOutput(String raw) {
        try {
            var root = objectMapper.readTree(raw);
            var goalIds = new LinkedHashMap<String, UUID>();
            var requirementIds = new LinkedHashMap<String, Map<String, UUID>>();
            var requirementDrafts = new LinkedHashMap<String, List<GoalRequirementPolicy.Draft>>();
            var goalNodes = requiredArray(root, "goals");
            for (var goal : goalNodes) {
                var key = requiredText(goal, "key");
                if (goalIds.putIfAbsent(key, UUID.randomUUID()) != null) throw new IllegalStateException("Goal key 重复");
                var proposed = requiredArray(goal, "requirements").stream()
                        .map(requirement -> new GoalRequirementPolicy.Draft(
                                requiredText(requirement, "key"), requiredText(requirement, "description")))
                        .toList();
                var drafts = GoalRequirementPolicy.normalize(requiredText(goal, "question"),
                        goal.path("goalType").asText(""), proposed);
                var ids = new LinkedHashMap<String, UUID>();
                for (var requirement : drafts) {
                    if (ids.putIfAbsent(requirement.key(), UUID.randomUUID()) != null) {
                        throw new IllegalStateException("Requirement key 重复");
                    }
                }
                requirementIds.put(key, ids);
                requirementDrafts.put(key, drafts);
            }

            var goals = new ArrayList<GoalPlan>();
            for (var node : goalNodes) {
                var key = requiredText(node, "key");
                var goalId = goalIds.get(key);
                var requirements = new ArrayList<RequirementPlan>();
                for (var requirement : requirementDrafts.get(key)) {
                    requirements.add(new RequirementPlan(
                            requirementIds.get(key).get(requirement.key()), goalId, requirement.description()));
                }
                var targetIds = new LinkedHashSet<>(requirementIds.get(key).values());
                var queries = requiredArray(node, "primaryQueries");
                if (queries.size() != 2) throw new IllegalStateException("每个 Goal 必须恰好包含两个首轮 Query");
                SearchQuery keyword = null;
                SearchQuery semantic = null;
                for (var query : queries) {
                    var role = SearchQueryRole.valueOf(requiredText(query, "role"));
                    var mode = SearchMode.valueOf(requiredText(query, "searchMode"));
                    var queryText = requiredText(query, "text");
                    if (role == SearchQueryRole.PRIMARY_KEYWORD) {
                        queryText = KeywordQueryPolicy.normalize(queryText, requiredText(node, "question"));
                    }
                    var parsed = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY, role,
                            queryText, mode, targetIds);
                    if (role == SearchQueryRole.PRIMARY_KEYWORD) keyword = parsed;
                    if (role == SearchQueryRole.PRIMARY_SEMANTIC) semantic = parsed;
                }
                var pair = new QueryPair(goalId, ResearchPhase.PRIMARY, keyword, semantic);
                goals.add(new GoalPlan(goalId, requiredText(node, "question"), requirements, pair));
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
            throw new IllegalStateException("Deep RAG Request Analysis 输出不合法", failure);
        }
    }

    private RequestAnalysis applyOriginalQueryAnchors(RequestAnalysis analysis, String originalQuestion) {
        var targets = originalTargets(originalQuestion);
        if (targets.isEmpty()) return analysis;
        var sourceGoals = analysis.goals();
        var objectiveRequirements = analysis.objectiveRequirements();
        var answerConstraints = analysis.answerConstraints();
        if (targets.size() == 1 && sourceGoals.size() > 1) {
            var target = targets.getFirst();
            var selectedGoal = sourceGoals.stream()
                    .max(java.util.Comparator.comparingInt(goal -> targetScore(target, goal)))
                    .orElseThrow();
            sourceGoals = List.of(selectedGoal);
            objectiveRequirements = List.of(new ObjectiveRequirement(
                    analysis.objectiveRequirements().getFirst().id(), target, true,
                    java.util.Set.of(selectedGoal.id())));
            answerConstraints = List.of();
        }
        if (targets.size() != sourceGoals.size()) return analysis;
        var alignedTargets = alignTargets(targets, sourceGoals);
        var goals = new ArrayList<GoalPlan>();
        for (int index = 0; index < sourceGoals.size(); index++) {
            var goal = sourceGoals.get(index);
            var target = alignedTargets.get(index);
            var pair = goal.primaryQueryPair();
            var keyword = pair.keywordQuery();
            var semantic = pair.semanticQuery();
            var canonicalTarget = preserveEvidenceFacet(target, KeywordQueryPolicy.canonicalGoal(target));
            var coreRequirement = new RequirementPlan(goal.requirements().getFirst().id(), goal.id(),
                    requirementDescriptionForTarget(canonicalTarget));
            var targetRequirementIds = java.util.Set.of(coreRequirement.id());
            var normalizedKeyword = KeywordQueryPolicy.normalize(keyword.text(), canonicalTarget);
            var normalizedSemantic = semanticQueryForTarget(canonicalTarget);
            var normalizedPair = new QueryPair(goal.id(), ResearchPhase.PRIMARY,
                    new SearchQuery(keyword.queryId(), keyword.goalId(), keyword.phase(), keyword.role(),
                            normalizedKeyword, keyword.searchMode(), targetRequirementIds),
                    new SearchQuery(semantic.queryId(), semantic.goalId(), semantic.phase(), semantic.role(),
                            normalizedSemantic, semantic.searchMode(), targetRequirementIds));
            goals.add(new GoalPlan(goal.id(), canonicalTarget, List.of(coreRequirement), normalizedPair));
        }
        var objective = targets.size() == 1 ? targets.getFirst() : "分别回答：" + String.join("；", targets);
        return new RequestAnalysis(objective, objectiveRequirements, answerConstraints, goals);
    }

    List<String> originalTargets(String originalQuestion) {
        if (originalQuestion == null || originalQuestion.isBlank()) return List.of();
        var value = originalQuestion.strip();
        var namedTarget = java.util.regex.Pattern.compile("只知道要处理[“\\\"]([^”\\\"]+)[”\\\"]")
                .matcher(value);
        if (namedTarget.find()) return List.of(namedTarget.group(1).strip());
        var ticketTarget = java.util.regex.Pattern.compile("需要完成\\s*([^，,。”\\\"]+)")
                .matcher(value);
        if (ticketTarget.find()) return List.of(ticketTarget.group(1).strip());
        if (!value.contains("；") && !value.contains(";")) return List.of();
        int colon = firstPositive(value.indexOf('：'), value.indexOf(':'));
        if (colon >= 0 && colon + 1 < value.length()) value = value.substring(colon + 1);
        int instruction = value.indexOf('。');
        if (instruction < 0) instruction = value.indexOf("请分别");
        if (instruction > 0) value = value.substring(0, instruction);
        var targets = new ArrayList<String>();
        for (var target : value.split("[；;]")) {
            var normalized = target.strip().replaceFirst("^[，。,.]+", "")
                    .replaceFirst("[，。,.]+$", "")
                    .replaceFirst("^第[一二三123]阶段", "").strip();
            if (!normalized.isBlank()) targets.add(normalized);
        }
        return List.copyOf(targets);
    }

    private List<String> alignTargets(List<String> targets, List<GoalPlan> goals) {
        var permutations = new ArrayList<List<String>>();
        collectPermutations(targets, new boolean[targets.size()], new ArrayList<>(), permutations);
        return permutations.stream().max(java.util.Comparator.comparingInt(permutation -> {
            int score = 0;
            for (int index = 0; index < goals.size(); index++) {
                score += targetScore(permutation.get(index), goals.get(index));
            }
            return score;
        })).orElse(targets);
    }

    private void collectPermutations(
            List<String> targets,
            boolean[] used,
            List<String> current,
            List<List<String>> permutations
    ) {
        if (current.size() == targets.size()) {
            permutations.add(List.copyOf(current));
            return;
        }
        for (int index = 0; index < targets.size(); index++) {
            if (used[index]) continue;
            used[index] = true;
            current.add(targets.get(index));
            collectPermutations(targets, used, current, permutations);
            current.removeLast();
            used[index] = false;
        }
    }

    private int targetScore(String target, GoalPlan goal) {
        var source = compact(target);
        var candidate = compact(goal.question() + " " + goal.primaryQueryPair().keywordQuery().text()
                + " " + goal.primaryQueryPair().semanticQuery().text());
        int score = 0;
        var parts = target.split("中的", 2);
        for (var part : parts) {
            var normalized = compact(part.replace("能力定位", "").replace("核心定位", "")
                    .replace("约束与做法", ""));
            if (normalized.length() >= 2 && candidate.contains(normalized)) score += normalized.length() * 10;
        }
        var seen = new LinkedHashSet<String>();
        for (int index = 0; index + 1 < source.length(); index++) {
            var gram = source.substring(index, index + 2);
            if (seen.add(gram) && candidate.contains(gram)) score++;
        }
        return score;
    }

    private String semanticQueryForTarget(String target) {
        var parts = target.split("中的", 2);
        var subject = parts[0].strip();
        if (target.contains("核心定位") || target.contains("能力定位")) {
            return limit("查找并说明" + subject + "的定义或身份、架构或组成、角色职责关系及主要用途");
        }
        if (target.contains("约束与做法") || target.contains("条件与做法")) {
            return limit("查找并说明" + subject + "资料明确给出的前提条件、边界限制、关键做法和必要步骤");
        }
        if (target.contains("简介") || target.contains("概述") || target.contains("介绍")) {
            return limit("查找并说明" + subject + "的定义、定位、组成、主要能力和用途");
        }
        if (parts.length == 2) {
            return limit("查找并说明" + parts[0].strip() + "文档中的" + parts[1].strip());
        }
        return limit("查找并说明" + target.strip());
    }

    private String requirementDescriptionForTarget(String target) {
        if (target.contains("核心定位") || target.contains("能力定位")) {
            return "核验“" + target + "”对应对象的定义或身份、架构或组成、角色职责关系及主要用途";
        }
        if (target.contains("约束与做法") || target.contains("条件与做法")) {
            return "核验“" + target + "”对应资料明确给出的前提条件、边界限制、关键做法和必要步骤";
        }
        if (target.contains("简介") || target.contains("概述") || target.contains("介绍")) {
            return "核验“" + target + "”对应对象的定义、定位、组成、主要能力和用途";
        }
        return "直接核验“" + target + "”对应章节明确给出的定义、要求、参数、步骤或限制";
    }

    private String preserveEvidenceFacet(String originalTarget, String canonicalTarget) {
        var originalParts = originalTarget.split("中的", 2);
        var canonicalParts = canonicalTarget.split("中的", 2);
        if (originalParts.length != 2 || canonicalParts.length != 2) return canonicalTarget;
        var facet = originalParts[1].strip();
        if (facet.contains("核心定位") || facet.contains("能力定位")
                || facet.contains("约束与做法") || facet.contains("条件与做法")) {
            return canonicalParts[0].strip() + "中的" + facet;
        }
        return canonicalTarget;
    }

    private String compact(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s，。！？；：,.!?;:()（）\\[\\]{}]", "");
    }

    private int firstPositive(int left, int right) {
        if (left < 0) return right;
        if (right < 0) return left;
        return Math.min(left, right);
    }

    private Map<String, Object> boundedInput(
            String question,
            List<String> messages,
            DeepRagLimits limits,
            String selectedPrompt
    ) {
        var recent = messages == null ? new ArrayList<String>()
                : new ArrayList<>(messages.stream().filter(java.util.Objects::nonNull).limit(6).toList());
        var boundedQuestion = truncate(question, 2_200);
        var input = analysisInput(boundedQuestion, recent);
        while (estimated(input, selectedPrompt) > limits.tokens().requestAnalysisInput() && !recent.isEmpty()) {
            recent.removeFirst();
            input = analysisInput(boundedQuestion, recent);
        }
        if (estimated(input, selectedPrompt) > limits.tokens().requestAnalysisInput()) {
            boundedQuestion = truncate(question, Math.max(200, limits.tokens().requestAnalysisInput() / 2));
            input = analysisInput(boundedQuestion, recent);
        }
        if (estimated(input, selectedPrompt) > limits.tokens().requestAnalysisInput()) {
            throw new IllegalStateException("Request Analysis 输入超过 Token 上限");
        }
        return input;
    }

    private Map<String, Object> analysisInput(String question, List<String> recent) {
        return Map.of("originalQuestion", question, "recentMessages", List.copyOf(recent),
                "maximumGoals", DeepRagLimits.MAX_GOALS,
                "maximumRequirementsPerGoal", DeepRagLimits.MAX_REQUIREMENTS_PER_GOAL,
                "requiredPrimaryQueriesPerGoal", 2);
    }

    private RequestAnalysis fallback(String question) {
        var bounded = question == null || question.isBlank() ? "用户问题" : question.strip();
        bounded = bounded.substring(0, Math.min(1_000, bounded.length()));
        var goalId = UUID.randomUUID();
        var requirementId = UUID.randomUUID();
        var requirement = new RequirementPlan(requirementId, goalId,
                "直接回答该问题语义核心所需的原文事实依据");
        var target = java.util.Set.of(requirementId);
        var keywordText = keywordFallback(bounded);
        var semanticText = bounded.equals(keywordText) ? "查找能够直接回答以下问题的资料：" + bounded : bounded;
        var pair = new QueryPair(goalId, ResearchPhase.PRIMARY,
                new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                        SearchQueryRole.PRIMARY_KEYWORD, keywordText, SearchMode.KEYWORD, target),
                new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                        SearchQueryRole.PRIMARY_SEMANTIC, limit(semanticText), SearchMode.SEMANTIC, target));
        var goal = new GoalPlan(goalId, bounded, List.of(requirement), pair);
        return new RequestAnalysis(limit(question),
                List.of(new ObjectiveRequirement(UUID.randomUUID(), "回答用户问题", true, java.util.Set.of(goalId))),
                List.of(), List.of(goal));
    }

    private String keywordFallback(String value) {
        var normalized = value.replaceAll("[，。！？；：,.!?;:()（）\\[\\]{}]", " ")
                .replaceAll("\\s+", " ").strip();
        var result = KeywordQueryPolicy.normalize(limit(normalized), value);
        return result.equals(value) ? limit(result + " 关键事实") : result;
    }

    private int estimated(Map<String, Object> input, String selectedPrompt) {
        return DeepModelInvoker.estimatedTokens(selectedPrompt)
                + DeepModelInvoker.estimatedTokens(objectMapper.valueToTree(input).toString());
    }

    private String truncate(String value, int tokens) {
        if (value == null || value.isBlank()) return "";
        if (DeepModelInvoker.estimatedTokens(value) <= tokens) return value;
        int low = 1, high = value.length(), best = 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            if (DeepModelInvoker.estimatedTokens(value.substring(0, middle)) <= tokens) {
                best = middle;
                low = middle + 1;
            } else high = middle - 1;
        }
        return value.substring(0, best);
    }

    private String limit(String value) {
        var safe = value == null || value.isBlank() ? "用户问题" : value.strip();
        return safe.substring(0, Math.min(300, safe.length()));
    }

    private boolean isBudgetOrDeadline(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            var message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("budget") || message.contains("预算") || message.contains("deadline")
                    || message.contains("timeout") || message.contains("超时")) return true;
        }
        return false;
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

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalStateException("字段必须是数组");
        var result = new ArrayList<String>();
        node.forEach(value -> result.add(value.asText()));
        return result;
    }

    private String requiredText(JsonNode node, String field) {
        var value = node.path(field).asText("").strip();
        if (value.isEmpty()) throw new IllegalStateException(field + " 不能为空");
        return value;
    }

    private static String resource(String path) {
        try (var input = RequestAnalyzer.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("缺少 Prompt: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt 无法读取: " + path, exception);
        }
    }
}
