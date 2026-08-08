package com.yanyue.rag.application.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.EvidenceItem;
import com.yanyue.rag.domain.agent.FactItem;
import com.yanyue.rag.domain.agent.QuestionPlan;
import com.yanyue.rag.domain.agent.SearchMode;
import com.yanyue.rag.domain.agent.SubQuestion;
import com.yanyue.rag.domain.agent.SubQuestionCoverage;
import com.yanyue.rag.domain.agent.SupportedSurface;
import com.yanyue.rag.domain.port.StructuredReasoningModelPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class AgentStructuredReasoner {
    private final StructuredReasoningModelPort model;
    private final ObjectMapper objectMapper;
    private final Map<String, String> prompts;

    public AgentStructuredReasoner(StructuredReasoningModelPort model, ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
        this.prompts = Map.of(
                "router", resource("prompts/router-v1.md"),
                "planner", resource("prompts/planner-v3.md"),
                "evidence", resource("prompts/evidence-v2.md"),
                "fact", resource("prompts/fact-v1.md"),
                "entailment", resource("prompts/entailment-v1.md"),
                "conflict", resource("prompts/conflict-v1.md"),
                "coverage", resource("prompts/coverage-v3.md"),
                "gap", resource("prompts/gap-v3.md")
        );
    }

    public IntentDecision classify(UUID profileId, String query) {
        return invoke(profileId, "intent-router", prompts.get("router"), json(Map.of("query", query)), root -> {
            var mode = RunMode.valueOf(requiredText(root, "mode"));
            if (mode == RunMode.AUTO) throw new IllegalStateException("Router cannot return AUTO");
            return new IntentDecision(mode, requiredText(root, "reason"));
        });
    }

    public QuestionPlan plan(UUID profileId, UUID runId, String objective, int maximum) {
        return invoke(profileId, "agent-planner", prompts.get("planner"),
                json(Map.of("objective", objective, "maximumSubQuestions", maximum)), root -> {
                    var values = requiredArray(root, "subQuestions");
                    if (values.isEmpty() || values.size() > maximum) {
                        throw new IllegalStateException("Planner returned an invalid sub-question count");
                    }
                    var ids = new LinkedHashMap<String, UUID>();
                    for (var value : values) {
                        var key = requiredText(value, "key");
                        if (ids.putIfAbsent(key, UUID.randomUUID()) != null) {
                            throw new IllegalStateException("Planner returned duplicate keys");
                        }
                    }
                    var questions = new ArrayList<SubQuestion>();
                    for (int index = 0; index < values.size(); index++) {
                        var value = values.get(index);
                        var key = requiredText(value, "key");
                        var dependencies = stringList(value.path("dependencies")).stream().map(dependency -> {
                            var id = ids.get(dependency);
                            if (id == null) throw new IllegalStateException("Planner returned an unknown dependency");
                            return id;
                        }).toList();
                        var priorIds = values.subList(0, index).stream()
                                .map(item -> ids.get(requiredText(item, "key"))).toList();
                        if (!priorIds.containsAll(dependencies)) {
                            throw new IllegalStateException("Planner dependencies must point to earlier questions");
                        }
                        questions.add(new SubQuestion(
                                ids.get(key), requiredText(value, "question"), stringList(value.path("expectedEvidence")),
                                Math.max(1, Math.min(5, value.path("priority").asInt(3))), dependencies,
                                searchMode(value.path("searchMode").asText("HYBRID")),
                                requiredText(value, "completionCondition")
                        ));
                    }
                    return new QuestionPlan(runId, objective, questions);
                });
    }

    public List<FactDraft> extractAndVerifyFacts(
            UUID profileId,
            SubQuestion question,
            List<EvidenceItem> evidence
    ) {
        if (evidence.isEmpty()) return List.of();
        var input = Map.of("subQuestion", question.question(), "evidence", evidence.stream().map(item -> Map.of(
                "id", item.id(), "quote", item.quote(), "documentVersionId", item.documentVersionId()
        )).toList());
        var proposed = invoke(profileId, "fact-extraction", prompts.get("fact"), json(input), root -> {
            var known = evidence.stream().map(EvidenceItem::id).collect(java.util.stream.Collectors.toSet());
            var result = new ArrayList<ProposedFact>();
            for (var value : requiredArray(root, "facts")) {
                var evidenceIds = stringList(value.path("evidenceIds")).stream().map(UUID::fromString).toList();
                if (evidenceIds.isEmpty() || !known.containsAll(evidenceIds)) {
                    throw new IllegalStateException("Fact references unknown evidence");
                }
                var confidence = value.path("confidence").asDouble(-1);
                if (confidence < 0 || confidence > 1) throw new IllegalStateException("Fact confidence is invalid");
                result.add(new ProposedFact(requiredText(value, "statement"), evidenceIds, confidence));
            }
            return List.copyOf(result);
        });
        if (proposed.isEmpty()) return List.of();
        var entailmentInput = Map.of("evidence", input.get("evidence"), "proposedFacts", proposed);
        return invoke(profileId, "fact-entailment", prompts.get("entailment"), json(entailmentInput), root -> {
            var judgments = new HashMap<Integer, Entailment>();
            for (var value : requiredArray(root, "judgments")) {
                var index = value.path("factIndex").asInt(-1);
                if (index < 0 || index >= proposed.size() || judgments.containsKey(index)) {
                    throw new IllegalStateException("Entailment returned an invalid fact index");
                }
                judgments.put(index, new Entailment(value.path("supported").asBoolean(false),
                        requiredText(value, "reason")));
            }
            if (judgments.size() != proposed.size()) throw new IllegalStateException("Entailment did not cover all facts");
            var drafts = new ArrayList<FactDraft>();
            for (int index = 0; index < proposed.size(); index++) {
                var fact = proposed.get(index);
                var judgment = judgments.get(index);
                drafts.add(new FactDraft(fact.statement(), fact.evidenceIds(), fact.confidence(),
                        judgment.supported(), judgment.reason()));
            }
            return List.copyOf(drafts);
        });
    }

    public List<EvidenceSpan> extractEvidenceSpans(
            UUID profileId,
            String subQuestion,
            List<EvidenceContext> contexts
    ) {
        if (contexts == null || contexts.isEmpty()) return List.of();
        return extractEvidenceSpansBatch(profileId,
                List.of(new EvidenceRequest("single", subQuestion, contexts)));
    }

    /**
     * 在一次结构化请求中为一批独立子问题抽取证据。上下文键全局唯一，
     * 因而模型可以返回扁平列表，服务端仍能按原始上下文逐条校验证据原文。
     */
    public List<EvidenceSpan> extractEvidenceSpansBatch(
            UUID profileId,
            List<EvidenceRequest> requests
    ) {
        if (requests == null || requests.isEmpty()) return List.of();
        var known = new LinkedHashMap<String, EvidenceContext>();
        var serializedRequests = new ArrayList<Map<String, Object>>();
        for (var request : requests) {
            if (request == null || request.contexts() == null || request.contexts().isEmpty()) continue;
            var serializedContexts = new ArrayList<Map<String, String>>();
            for (var context : request.contexts()) {
                if (context == null || context.key() == null || context.key().isBlank()
                        || context.text() == null || context.text().isBlank()) {
                    continue;
                }
                if (known.putIfAbsent(context.key(), context) != null) {
                    throw new IllegalStateException("Evidence extraction batch contains duplicate context keys");
                }
                serializedContexts.add(Map.of("key", context.key(), "text", context.text()));
            }
            if (!serializedContexts.isEmpty()) {
                serializedRequests.add(Map.of(
                        "subQuestionKey", request.subQuestionKey(),
                        "subQuestion", request.subQuestion(),
                        "contexts", serializedContexts));
            }
        }
        if (known.isEmpty()) return List.of();
        var input = Map.of("requests", serializedRequests);
        return invoke(profileId, "evidence-extraction", prompts.get("evidence"), json(input), root -> {
            var result = new ArrayList<EvidenceSpan>();
            var used = new HashSet<String>();
            for (var value : requiredArray(root, "items")) {
                var key = requiredText(value, "contextKey");
                var context = known.get(key);
                if (context == null || !used.add(key)) {
                    throw new IllegalStateException("Evidence extraction returned an unknown or duplicate context key");
                }
                var quote = requiredText(value, "quote");
                var located = locateVerbatimSpan(context.text(), quote);
                if (located == null) {
                    throw new IllegalStateException("Evidence quote is not a verbatim source span");
                }
                result.add(new EvidenceSpan(key, located.quote(), located.startOffset(), located.endOffset()));
            }
            return List.copyOf(result);
        });
    }

    /**
     * 仅接受能够定位到原文的证据片段，同时容许模型归一化 PDF 或 Markdown
     * 换行产生的空白；最终返回内容始终从原始文本中截取。
     */
    private LocatedSpan locateVerbatimSpan(String source, String quote) {
        var exactOffset = source.indexOf(quote);
        if (exactOffset >= 0) {
            return new LocatedSpan(quote, exactOffset, exactOffset + quote.length());
        }
        var normalizedQuote = quote.replaceAll("\\s+", "");
        if (normalizedQuote.isEmpty()) return null;
        var normalizedSource = new StringBuilder(source.length());
        var sourceOffsets = new ArrayList<Integer>();
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (Character.isWhitespace(value)) continue;
            normalizedSource.append(value);
            sourceOffsets.add(index);
        }
        var normalizedOffset = normalizedSource.indexOf(normalizedQuote);
        if (normalizedOffset < 0) return null;
        var start = sourceOffsets.get(normalizedOffset);
        var end = sourceOffsets.get(normalizedOffset + normalizedQuote.length() - 1) + 1;
        return new LocatedSpan(source.substring(start, end), start, end);
    }

    public CoverageReport coverage(
            UUID profileId,
            UUID runId,
            QuestionPlan plan,
            List<EvidenceItem> evidence,
            List<FactItem> facts
    ) {
        return coverage(profileId, runId, plan, evidence, facts, true);
    }

    /**
     * v2 轻量证据门禁：保留模型的语义充分性判断，同时由服务端强制要求
     * 每个已覆盖子问题至少具有一个真实的深读证据族。
     */
    public CoverageReport evidenceCoverage(
            UUID profileId,
            UUID runId,
            QuestionPlan plan,
            List<EvidenceItem> evidence
    ) {
        return coverage(profileId, runId, plan, evidence, List.of(), false);
    }

    private CoverageReport coverage(
            UUID profileId,
            UUID runId,
            QuestionPlan plan,
            List<EvidenceItem> evidence,
            List<FactItem> facts,
            boolean requireAcceptedFact
    ) {
        var keys = new LinkedHashMap<String, SubQuestion>();
        for (int index = 0; index < plan.subQuestions().size(); index++) {
            keys.put("q" + (index + 1), plan.subQuestions().get(index));
        }
        var input = Map.of(
                "subQuestions", keys.entrySet().stream().map(entry -> Map.of(
                        "key", entry.getKey(), "id", entry.getValue().id(), "question", entry.getValue().question(),
                        "completionCondition", entry.getValue().completionCondition())).toList(),
                "evidence", evidence,
                "facts", facts
        );
        var evidenceFamilies = new HashMap<UUID, Set<UUID>>();
        var deepEvidenceByQuestion = new HashMap<UUID, Map<UUID, EvidenceItem>>();
        for (var item : evidence) {
            if (!item.deepRead()) continue;
            evidenceFamilies.computeIfAbsent(item.subQuestionId(), ignored -> new HashSet<>())
                    .add(item.documentVersionId());
            deepEvidenceByQuestion.computeIfAbsent(item.subQuestionId(), ignored -> new HashMap<>())
                    .put(item.id(), item);
        }
        var acceptedQuestions = new HashSet<UUID>();
        var conflictingQuestions = new HashSet<UUID>();
        for (var fact : facts) {
            if (fact.status() == com.yanyue.rag.domain.agent.FactStatus.ACCEPTED) {
                acceptedQuestions.add(fact.subQuestionId());
            } else if (fact.status() == com.yanyue.rag.domain.agent.FactStatus.CONFLICTING) {
                conflictingQuestions.add(fact.subQuestionId());
            }
        }
        var operation = requireAcceptedFact ? "coverage-judge" : "evidence-judge";
        return invoke(profileId, operation, prompts.get("coverage"), json(input), root -> {
            var coveredKeys = new java.util.HashSet<String>();
            var items = new ArrayList<SubQuestionCoverage>();
            for (var value : requiredArray(root, "items")) {
                var key = requiredText(value, "key");
                var question = keys.get(key);
                if (question == null || !coveredKeys.add(key)) {
                    throw new IllegalStateException("Coverage returned an unknown or duplicate key");
                }
                int familyCount = evidenceFamilies.getOrDefault(question.id(), Set.of()).size();
                boolean hasAcceptedFact = acceptedQuestions.contains(question.id());
                boolean hasConflict = value.path("hasConflict").asBoolean(false)
                        || conflictingQuestions.contains(question.id());
                var gaps = new java.util.LinkedHashSet<>(stringList(value.path("gaps")));
                var supportedSurfaces = supportedSurfaces(
                        value.path("supportedSurfaces"), question,
                        deepEvidenceByQuestion.getOrDefault(question.id(), Map.of()));
                boolean modelCovered = value.path("covered").asBoolean(false);
                if (!modelCovered && gaps.isEmpty()) gaps.add("该子问题尚未满足完成条件");
                if (familyCount == 0) gaps.add("该子问题没有已深读证据族");
                if (requireAcceptedFact && !hasAcceptedFact) gaps.add("该子问题没有通过蕴含审核的事实");
                if (hasConflict) gaps.add("该子问题存在未解决的事实冲突");
                boolean covered = modelCovered && gaps.isEmpty()
                        && familyCount > 0 && (!requireAcceptedFact || hasAcceptedFact) && !hasConflict;
                if (covered && supportedSurfaces.isEmpty()) {
                    throw new IllegalStateException("Covered question has no supported surface");
                }
                items.add(new SubQuestionCoverage(
                        question.id(), covered, familyCount, List.copyOf(gaps), hasConflict,
                        supportedSurfaces));
            }
            if (items.size() != keys.size()) throw new IllegalStateException("Coverage did not cover all questions");
            return new CoverageReport(runId, items);
        });
    }

    public List<ConflictDraft> detectConflicts(UUID profileId, List<FactItem> acceptedFacts) {
        if (acceptedFacts.size() < 2) return List.of();
        return invoke(profileId, "fact-conflict", prompts.get("conflict"),
                json(Map.of("acceptedFacts", acceptedFacts)), root -> {
                    var groups = new ArrayList<ConflictDraft>();
                    var assigned = new java.util.HashSet<Integer>();
                    for (var value : requiredArray(root, "groups")) {
                        var indexes = new ArrayList<Integer>();
                        var raw = value.path("factIndexes");
                        if (!raw.isArray()) throw new IllegalStateException("factIndexes must be an array");
                        raw.forEach(item -> {
                            var index = item.asInt(-1);
                            if (index < 0 || index >= acceptedFacts.size() || !assigned.add(index)) {
                                throw new IllegalStateException("Conflict references an invalid or duplicate fact index");
                            }
                            indexes.add(index);
                        });
                        if (indexes.size() < 2) throw new IllegalStateException("A conflict group needs at least two facts");
                        groups.add(new ConflictDraft(List.copyOf(indexes), requiredText(value, "reason")));
                    }
                    return List.copyOf(groups);
                });
    }

    private List<SupportedSurface> supportedSurfaces(
            JsonNode value,
            SubQuestion question,
            Map<UUID, EvidenceItem> knownEvidence
    ) {
        if (value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray() || value.size() > 8) {
            throw new IllegalStateException("supportedSurfaces must be an array with at most 8 items");
        }
        var statements = new HashSet<String>();
        var result = new ArrayList<SupportedSurface>();
        for (var item : value) {
            var statement = requiredText(item, "statement").replaceAll("\\s+", " ");
            if (statement.length() > 300 || !statements.add(statement)) {
                throw new IllegalStateException("Supported surface statement is invalid or duplicated");
            }
            var rawEvidenceIds = stringList(item.path("evidenceIds"));
            if (rawEvidenceIds.isEmpty() || rawEvidenceIds.size() > 2) {
                throw new IllegalStateException("Supported surface needs 1 to 2 evidence IDs");
            }
            var evidenceIds = new ArrayList<UUID>();
            var seenEvidenceIds = new HashSet<UUID>();
            for (var rawEvidenceId : rawEvidenceIds) {
                UUID evidenceId;
                try {
                    evidenceId = UUID.fromString(rawEvidenceId);
                } catch (IllegalArgumentException failure) {
                    throw new IllegalStateException("Supported surface contains an invalid evidence ID", failure);
                }
                var evidence = knownEvidence.get(evidenceId);
                if (evidence == null || !evidence.deepRead()
                        || !evidence.subQuestionId().equals(question.id()) || !seenEvidenceIds.add(evidenceId)) {
                    throw new IllegalStateException("Supported surface references unknown or cross-question evidence");
                }
                evidenceIds.add(evidenceId);
            }
            result.add(new SupportedSurface(statement, evidenceIds));
        }
        return List.copyOf(result);
    }

    public List<GapQuery> gapQueries(
            UUID profileId,
            QuestionPlan plan,
            CoverageReport coverage,
            List<String> previousQueries
    ) {
        var keys = new LinkedHashMap<String, SubQuestion>();
        for (int index = 0; index < plan.subQuestions().size(); index++) keys.put("q" + (index + 1), plan.subQuestions().get(index));
        var uncovered = coverage.items().stream()
                .filter(item -> !item.covered() || item.hasConflict())
                .map(SubQuestionCoverage::subQuestionId)
                .collect(java.util.stream.Collectors.toSet());
        var coverageByQuestion = coverage.items().stream().collect(java.util.stream.Collectors.toMap(
                SubQuestionCoverage::subQuestionId, Function.identity()));
        var previousQueryKeys = previousQueries.stream()
                .filter(java.util.Objects::nonNull)
                .map(this::normalizeQuery)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return invoke(profileId, "gap-query", prompts.get("gap"), json(Map.of(
                "subQuestions", keys, "coverage", coverage, "previousQueries", previousQueries
        )), root -> {
            var result = new ArrayList<GapQuery>();
            var seenQueries = new java.util.LinkedHashSet<>(previousQueryKeys);
            var generatedQuestions = new HashSet<UUID>();
            for (var value : requiredArray(root, "queries")) {
                var question = resolveGapQuestion(keys, plan, requiredText(value, "key"));
                if (question == null || !uncovered.contains(question.id())
                        || generatedQuestions.contains(question.id())) continue;
                var query = requiredText(value, "query");
                var mode = searchMode(value.path("searchMode").asText("HYBRID"));
                if (isCrossQuestionQuery(query, question, plan)) {
                    query = fallbackGapQuery(question, coverageByQuestion.get(question.id()));
                    mode = SearchMode.HYBRID;
                }
                if (!seenQueries.add(normalizeQuery(query))) continue;
                result.add(new GapQuery(question.id(), query, mode));
                generatedQuestions.add(question.id());
            }
            for (var question : plan.subQuestions()) {
                if (!uncovered.contains(question.id()) || generatedQuestions.contains(question.id())) continue;
                var query = fallbackGapQuery(question, coverageByQuestion.get(question.id()));
                if (seenQueries.add(normalizeQuery(query))) {
                    result.add(new GapQuery(question.id(), query, SearchMode.HYBRID));
                }
            }
            return List.copyOf(result);
        });
    }

    private boolean isCrossQuestionQuery(String query, SubQuestion assigned, QuestionPlan plan) {
        int assignedScore = distinctiveAnchorScore(query, assigned, plan);
        int strongestOther = plan.subQuestions().stream()
                .filter(question -> !question.id().equals(assigned.id()))
                .mapToInt(question -> distinctiveAnchorScore(query, question, plan))
                .max().orElse(0);
        return strongestOther >= 2 && strongestOther > assignedScore;
    }

    private int distinctiveAnchorScore(String query, SubQuestion question, QuestionPlan plan) {
        var normalizedQuery = normalizeAnchor(query);
        var anchors = anchorNgrams(question.question());
        var otherAnchors = plan.subQuestions().stream()
                .filter(other -> !other.id().equals(question.id()))
                .flatMap(other -> anchorNgrams(other.question()).stream())
                .collect(java.util.stream.Collectors.toSet());
        anchors.removeAll(otherAnchors);
        return anchors.stream()
                .filter(normalizedQuery::contains)
                .mapToInt(String::length)
                .max().orElse(0);
    }

    private Set<String> anchorNgrams(String value) {
        var normalized = normalizeAnchor(value);
        var anchors = new HashSet<String>();
        int maximum = Math.min(6, normalized.length());
        for (int length = 2; length <= maximum; length++) {
            for (int start = 0; start <= normalized.length() - length; start++) {
                var anchor = normalized.substring(start, start + length);
                if (anchor.chars().anyMatch(Character::isLetterOrDigit)) anchors.add(anchor);
            }
        }
        return anchors;
    }

    private String normalizeAnchor(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}_.-]+", "");
    }

    private String fallbackGapQuery(SubQuestion question, SubQuestionCoverage coverage) {
        var gap = coverage == null || coverage.gaps().isEmpty() ? "补充直接原文证据" : coverage.gaps().getFirst();
        var query = (question.question() + " " + gap).strip().replaceAll("\\s+", " ");
        return query.substring(0, Math.min(300, query.length()));
    }

    private String normalizeQuery(String query) {
        return query.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private SubQuestion resolveGapQuestion(
            Map<String, SubQuestion> keys,
            QuestionPlan plan,
            String rawKey
    ) {
        var normalized = rawKey.strip().toLowerCase(java.util.Locale.ROOT);
        var keyed = keys.get(normalized);
        if (keyed != null) return keyed;
        try {
            var id = UUID.fromString(normalized);
            return plan.subQuestions().stream()
                    .filter(question -> question.id().equals(id))
                    .findFirst().orElse(null);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private <T> T invoke(
            UUID profileId,
            String operation,
            String systemPrompt,
            String userPrompt,
            Function<JsonNode, T> parser
    ) {
        var output = model.completeJson(profileId, operation, systemPrompt, userPrompt);
        RuntimeException validationFailure;
        try {
            return parser.apply(parse(output));
        } catch (RuntimeException failure) {
            validationFailure = failure;
        }
        // 传输重试统一由模型适配器负责；这里只修复已经返回但不符合契约的结构化输出。
        if (output == null) throw validationFailure;
        var repairPrompt = "Return only corrected JSON matching the requested schema.\nOriginal input:\n"
                + userPrompt + "\nPrevious output:\n" + output
                + "\nValidation error:\n" + safeMessage(validationFailure);
        var repaired = model.completeJson(profileId, operation + "-repair", systemPrompt, repairPrompt);
        return parser.apply(parse(repaired));
    }

    private JsonNode parse(String value) {
        try {
            var stripped = value.strip();
            if (stripped.startsWith("```")) {
                var start = stripped.indexOf('\n');
                var end = stripped.lastIndexOf("```");
                if (start >= 0 && end > start) stripped = stripped.substring(start + 1, end).strip();
            }
            return objectMapper.readTree(stripped);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Structured model returned invalid JSON", exception);
        }
    }

    private List<JsonNode> requiredArray(JsonNode root, String field) {
        var value = root.path(field);
        if (!value.isArray()) throw new IllegalStateException(field + " must be an array");
        var result = new ArrayList<JsonNode>();
        value.forEach(result::add);
        return result;
    }

    private String requiredText(JsonNode root, String field) {
        var value = root.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException(field + " must be text");
        return value.asText().strip();
    }

    private List<String> stringList(JsonNode value) {
        if (!value.isArray()) return List.of();
        var result = new ArrayList<String>();
        value.forEach(item -> {
            if (!item.isTextual()) throw new IllegalStateException("Expected an array of strings");
            if (!item.asText().isBlank()) result.add(item.asText().strip());
        });
        return List.copyOf(result);
    }

    private SearchMode searchMode(String value) {
        try {
            return SearchMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown search mode: " + value, exception);
        }
    }

    private String resource(String path) {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Prompt resource was not found: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read prompt resource: " + path, exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize structured model input", exception);
        }
    }

    private String safeMessage(Throwable failure) {
        var message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.substring(0, Math.min(500, message.length()));
    }

    public record IntentDecision(RunMode mode, String reason) {
    }

    public record FactDraft(
            String statement,
            List<UUID> evidenceIds,
            double confidence,
            boolean supported,
            String reason
    ) {
    }

    public record GapQuery(UUID subQuestionId, String query, SearchMode searchMode) {
    }

    public record EvidenceContext(String key, String text) {
    }

    public record EvidenceRequest(
            String subQuestionKey,
            String subQuestion,
            List<EvidenceContext> contexts
    ) {
    }

    public record EvidenceSpan(String contextKey, String quote, int startOffset, int endOffset) {
    }

    public record ConflictDraft(List<Integer> factIndexes, String reason) {
    }

    private record ProposedFact(String statement, List<UUID> evidenceIds, double confidence) {
    }

    private record Entailment(boolean supported, String reason) {
    }

    private record LocatedSpan(String quote, int startOffset, int endOffset) {
    }
}
