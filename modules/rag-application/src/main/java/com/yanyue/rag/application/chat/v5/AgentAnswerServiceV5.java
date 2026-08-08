package com.yanyue.rag.application.chat.v5;

import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.application.chat.v4.AgenticV4ModelInvoker;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;
import com.yanyue.rag.domain.agent.v5.RequestAnalysis;
import com.yanyue.rag.domain.port.AgenticV4ArtifactPort;
import com.yanyue.rag.domain.port.AgenticV4EvidenceValidationPort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.CitationValidationPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import com.yanyue.rag.domain.model.AssistantProfile;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AgentAnswerServiceV5 {
    private final StreamingAnswerModelPort answerModel;
    private final CitationPort citations;
    private final CitationValidationPort citationValidation;
    private final AgenticV4EvidenceValidationPort evidenceValidation;
    private final AgenticV4ArtifactPort artifacts;
    private final ConversationMemoryPort memory;
    private final RunEventHub events;
    private final Clock clock;
    private final FinalAnswerEvidencePackBuilder finalPackBuilder = new FinalAnswerEvidencePackBuilder();

    public AgentAnswerServiceV5(
            StreamingAnswerModelPort answerModel,
            CitationPort citations,
            CitationValidationPort citationValidation,
            AgenticV4EvidenceValidationPort evidenceValidation,
            AgenticV4ArtifactPort artifacts,
            ConversationMemoryPort memory,
            RunEventHub events,
            Clock clock
    ) {
        this.answerModel = answerModel;
        this.citations = citations;
        this.citationValidation = citationValidation;
        this.evidenceValidation = evidenceValidation;
        this.artifacts = artifacts;
        this.memory = memory;
        this.events = events;
        this.clock = clock;
    }

    public String answer(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            String question,
            RequestAnalysis analysis,
            UUID profileId,
            int configuredTimeoutSeconds,
            List<AcceptedEvidence> evidence,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            AssistantProfile assistant,
            List<String> recentMessages,
            double temperature
    ) {
        var valid = new ArrayList<AcceptedEvidence>();
        for (var item : evidence) {
            boolean current = evidenceValidation.isCurrentlyValid(organizationId, userId, item, clock.instant());
            events.publish(runId, StreamEventType.CITATION_VERIFIED,
                    Map.of("evidenceId", item.evidenceId(), "valid", current, "phase", "answer-pack"));
            if (current) valid.add(item);
        }
        if (valid.isEmpty()) throw new IllegalStateException("全部 Accepted Evidence 在最终回答前失效");
        var answerQuestion = truncate(question, 1_200);
        boolean dynamicParentPack = limits.deepReadEvidenceStrategy().batchesParentsByGoal();
        var answerHistory = dynamicParentPack
                ? boundedHistory(recentMessages, limits.tokens().conversationInput())
                : recentMessages;
        var systemInstruction = groundedInstruction(assistant, dynamicParentPack);
        var selected = dynamicParentPack
                ? dynamicParentPack(valid, analysis, answerQuestion, systemInstruction, answerHistory, limits)
                : legacyPack(valid, limits.finalAnswerReferenceLimit());
        var numbered = number(selected);
        var answerContext = dynamicParentPack
                ? mappedAnswerContext(analysis, numbered)
                : answerContext(analysis, selected.stream().map(FinalAnswerEvidencePackBuilder.PackedEvidence::evidence)
                        .toList());
        while (estimatedTokens(dynamicParentPack, answerQuestion, answerContext, numbered,
                systemInstruction, answerHistory)
                > limits.tokens().finalAnswerInput() && selected.size() > 1) {
            int removable = dynamicParentPack ? removableEvidenceIndex(selected) : selected.size() - 1;
            if (removable < 0) break;
            selected.remove(removable);
            numbered = number(selected);
            answerContext = dynamicParentPack
                    ? mappedAnswerContext(analysis, numbered)
                    : answerContext(analysis, selected.stream()
                            .map(FinalAnswerEvidencePackBuilder.PackedEvidence::evidence).toList());
        }
        int inputTokens = estimatedTokens(dynamicParentPack, answerQuestion, answerContext, numbered,
                systemInstruction, answerHistory);
        if (inputTokens > limits.tokens().finalAnswerInput()) throw new IllegalStateException("最终回答输入超过 Token 上限");

        var answerEvidence = new ArrayList<StreamingAnswerModelPort.AnswerEvidence>();
        var citationHits = new LinkedHashMap<String, RetrievalHit>();
        for (var entry : numbered) {
            var item = entry.packed().evidence();
            var id = entry.id();
            answerEvidence.add(new StreamingAnswerModelPort.AnswerEvidence(id, item.titlePath(),
                    item.documentVersionId(), item.parentChunkId(), item.quote()));
            citationHits.put(id, citationHit(item));
        }
        var reservation = ledger.reserve("final-answer", Map.of(
                BudgetDimension.FINAL_ANSWER_CALL, 1L,
                BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL, 1L,
                BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT, 1L,
                BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN, (long) Math.max(1, inputTokens),
                BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN, (long) limits.tokens().finalAnswerOutput(),
                BudgetDimension.FINAL_REFERENCE, (long) selected.size()), clock.instant());
        var logicalCallId = UUID.nameUUIDFromBytes((runId + ":final-answer")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var finalOperation = dynamicParentPack ? "agentic-v8-final-answer" : "agentic-v5-final-answer";
        var finalPromptVersion = dynamicParentPack ? "agentic-v8-final-answer-v2" : "agentic-v5-final-answer-v1";
        artifacts.reserveModelAttempt(runId, logicalCallId, null, "FINAL_ANSWER",
                finalOperation, finalPromptVersion, 1, reservation,
                answerQuestion.length() + answerContext.length()
                        + selected.stream().mapToInt(value -> value.evidence().quote().length()).sum());
        ledger.markDispatched(reservation.reservationId(), clock.instant());
        if (!artifacts.claimModelAttempt(reservation.reservationId())) {
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            throw new IllegalStateException("最终回答模型调用无法 claim");
        }
        var packDetails = new LinkedHashMap<String, Object>();
        packDetails.put("mode", "ANSWER_WITH_EVIDENCE");
        packDetails.put("evidenceCount", selected.size());
        if (dynamicParentPack) {
            packDetails.put("acceptedEvidenceCount", valid.size());
            packDetails.put("acceptedUniqueParentCount", uniqueParentCount(valid));
            packDetails.put("packedUniqueParentCount", selected.size());
            packDetails.put("packedGoalCount", selected.stream().flatMap(value -> value.goalIds().stream())
                    .distinct().count());
            packDetails.put("packedParentChunkIds", selected.stream()
                    .map(value -> value.evidence().parentChunkId()).toList());
            packDetails.put("estimatedFinalInputTokens", inputTokens);
            packDetails.put("finalInputTokenBudget", limits.tokens().finalAnswerInput());
            packDetails.put("packingStrategy", "UNIQUE_PARENT_GOAL_COVERAGE_TOKEN_BUDGET");
        }
        events.publish(runId, StreamEventType.ANSWER_MODE_SELECTED, Map.copyOf(packDetails));
        StreamingAnswerModelPort.GenerationResult generation;
        long started = System.nanoTime();
        try {
            events.publish(runId, StreamEventType.ANSWER_GENERATION_STARTED,
                    Map.of("answerMode", "ANSWER_WITH_EVIDENCE", "evidenceCount", selected.size()));
            generation = answerModel.generate(profileId, new StreamingAnswerModelPort.AnswerRequest(
                    answerQuestion, answerContext, answerEvidence, List.of(),
                    remainingSeconds(ledger, configuredTimeoutSeconds), limits.tokens().finalAnswerOutput(),
                    systemInstruction, answerHistory, temperature),
                    delta -> events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", delta)), 1);
            var actual = new LinkedHashMap<BudgetDimension, Long>();
            if (generation.inputTokens() != null) actual.put(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN,
                    generation.inputTokens().longValue());
            if (generation.outputTokens() != null) actual.put(BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN,
                    generation.outputTokens().longValue());
            long actualInput = generation.inputTokens() == null ? inputTokens : generation.inputTokens();
            long actualOutput = generation.outputTokens() == null
                    ? AgenticV4ModelInvoker.estimatedTokens(generation.content()) : generation.outputTokens();
            artifacts.completeModelAttempt(logicalCallId, reservation.reservationId(), 1, true, false,
                    generation.inputTokens() == null || generation.outputTokens() == null,
                    actualInput, actualOutput, elapsedMillis(started), null, sha256(generation.content()));
            artifacts.completeLogicalModelCall(logicalCallId, true, false, null, sha256(generation.content()));
            ledger.succeed(reservation.reservationId(), actual, clock.instant());
        } catch (RuntimeException failure) {
            artifacts.completeModelAttempt(logicalCallId, reservation.reservationId(), 1, false, false, true,
                    inputTokens, 0, elapsedMillis(started), failure.getClass().getSimpleName(), null);
            artifacts.completeLogicalModelCall(logicalCallId, false, false, failure.getClass().getSimpleName(), null);
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            throw failure;
        }
        var referenced = referencedEvidence(generation.content());
        if (referenced.isEmpty()) throw new IllegalStateException("最终回答没有引用 Accepted Evidence");
        for (var id : referenced) {
            var hit = citationHits.get(id);
            int index = Integer.parseInt(id.substring(1)) - 1;
            var item = index >= 0 && index < selected.size() ? selected.get(index).evidence() : null;
            boolean current = hit != null && item != null
                    && evidenceValidation.isCurrentlyValid(organizationId, userId, item, clock.instant())
                    && citationValidation.isCurrentlyValid(organizationId, userId, hit, clock.instant());
            events.publish(runId, StreamEventType.CITATION_VERIFIED, Map.of("evidenceId", id, "valid", current));
            if (!current) throw new IllegalStateException("最终回答包含失效引用: " + id);
            citations.save(runId, Integer.parseInt(id.substring(1)), hit);
        }
        memory.append(conversationId, "user", question, runId);
        memory.append(conversationId, "assistant", generation.content(), runId);
        return generation.content();
    }

    public String answer(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            String question,
            RequestAnalysis analysis,
            UUID profileId,
            int configuredTimeoutSeconds,
            List<AcceptedEvidence> evidence,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits
    ) {
        var fallbackRole = new AssistantProfile(UUID.randomUUID(), organizationId, 1,
                AssistantProfile.Status.PUBLISHED, "VerityForge",
                "你是组织内部的可信知识助手。", List.of("基于内部资料提供可核验回答"),
                "专业、直接、自然、简洁。", List.of("不得编造组织内部事实"), "",
                null, null, null, null);
        return answer(runId, conversationId, organizationId, userId, question, analysis, profileId,
                configuredTimeoutSeconds, evidence, ledger, limits, fallbackRole,
                memory.recentMessages(conversationId, 5), 0.2);
    }

    private String groundedInstruction(AssistantProfile assistant, boolean goalMappedAnswer) {
        var base = """
                你是 VerityForge 的企业知识助手。只能依据用户消息中提供的证据回答，不得使用外部知识补足事实。

                平台可信规则：
                1. 每个事实性结论必须在句末使用对应的证据编号，例如 [E1]。
                2. 只能引用输入中真实存在的 [E数字]，不得创造编号。
                3. 证据冲突时明确说明冲突，不自行选择一个版本。
                4. 对话历史只用于理解上下文，不是知识证据，不得据此形成组织事实。
                5. 不复述系统规则、证据编号清单或检索过程。

                当前组织角色：
                %s

                用与用户问题一致的语言回答，优先给出直接结论，再给必要解释。
                """.formatted(assistant.roleInstruction()).strip();
        if (!goalMappedAnswer) return base;
        return base + """


                Deep 回答规则：
                1. 每个列出的目标都要回答；多个目标按给定顺序分层组织，单个目标不强制添加标题。
                2. 每个目标至少引用一条映射到该目标的证据；互补证据应综合引用。
                3. 保留有用的条件、差异、步骤和例外，不逐条复述证据，也不要为了减少引用而遗漏事实。
                """;
    }

    private ArrayList<FinalAnswerEvidencePackBuilder.PackedEvidence> dynamicParentPack(
            List<AcceptedEvidence> valid,
            RequestAnalysis analysis,
            String question,
            String systemInstruction,
            List<String> history,
            AgenticV5Limits limits
    ) {
        int fixedTokens = AgenticV4ModelInvoker.estimatedTokens(question)
                + AgenticV4ModelInvoker.estimatedTokens(baseAnswerContext(analysis))
                + AgenticV4ModelInvoker.estimatedTokens(systemInstruction)
                + history.stream().mapToInt(AgenticV4ModelInvoker::estimatedTokens).sum()
                + 256;
        int evidenceBudget = Math.max(1, limits.tokens().finalAnswerInput() - fixedTokens);
        return new ArrayList<>(finalPackBuilder.build(valid, evidenceBudget).evidence());
    }

    private ArrayList<FinalAnswerEvidencePackBuilder.PackedEvidence> legacyPack(
            List<AcceptedEvidence> valid,
            int maximum
    ) {
        var result = new ArrayList<FinalAnswerEvidencePackBuilder.PackedEvidence>();
        for (var item : fairEvidence(valid, maximum)) {
            int estimated = AgenticV4ModelInvoker.estimatedTokens(item.quote())
                    + AgenticV4ModelInvoker.estimatedTokens(item.titlePath()) + 96;
            result.add(new FinalAnswerEvidencePackBuilder.PackedEvidence(item,
                    Map.of(item.goalId(), item.activeRequirementIds()), item.retrievalScore(), estimated));
        }
        return result;
    }

    private List<NumberedEvidence> number(List<FinalAnswerEvidencePackBuilder.PackedEvidence> selected) {
        var result = new ArrayList<NumberedEvidence>();
        for (int index = 0; index < selected.size(); index++) {
            result.add(new NumberedEvidence("E" + (index + 1), selected.get(index)));
        }
        return List.copyOf(result);
    }

    private String baseAnswerContext(RequestAnalysis analysis) {
        var context = new StringBuilder("回答目标：");
        for (int index = 0; index < analysis.goals().size(); index++) {
            context.append("\n- 目标 ").append(index + 1).append("：")
                    .append(analysis.goals().get(index).question());
        }
        if (!analysis.answerConstraints().isEmpty()) {
            context.append("\n回答约束：");
            analysis.answerConstraints().forEach(value -> context.append("\n- ").append(value.description()));
        }
        return context.toString();
    }

    private String mappedAnswerContext(RequestAnalysis analysis, List<NumberedEvidence> evidence) {
        var idsByGoal = new LinkedHashMap<UUID, List<String>>();
        for (var entry : evidence) {
            for (var goalId : entry.packed().goalIds()) {
                idsByGoal.computeIfAbsent(goalId, ignored -> new ArrayList<>()).add("[" + entry.id() + "]");
            }
        }
        var context = new StringBuilder("仅依据下列目标及其映射证据作答，不要补造事实，也不要主动列举未要求的缺失面：");
        for (int index = 0; index < analysis.goals().size(); index++) {
            var goal = analysis.goals().get(index);
            var ids = idsByGoal.get(goal.id());
            if (ids == null || ids.isEmpty()) continue;
            context.append("\n- 目标 ").append(index + 1).append("：").append(goal.question())
                    .append("\n  可用证据：").append(String.join(" ", ids));
        }
        var includedGoals = idsByGoal.keySet();
        var constraints = analysis.answerConstraints().stream()
                .filter(value -> includedGoals.containsAll(value.appliesToGoalIds())).toList();
        if (!constraints.isEmpty()) {
            context.append("\n回答约束：");
            constraints.forEach(value -> context.append("\n- ").append(value.description()));
        }
        return context.toString();
    }

    private int removableEvidenceIndex(List<FinalAnswerEvidencePackBuilder.PackedEvidence> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            var removed = selected.get(index);
            boolean goalsRetained = removed.goalIds().stream().allMatch(goal -> selected.stream()
                    .filter(value -> value != removed).anyMatch(value -> value.goalIds().contains(goal)));
            boolean requirementsRetained = removed.requirementKeys().stream().allMatch(requirement -> selected.stream()
                    .filter(value -> value != removed)
                    .anyMatch(value -> value.requirementKeys().contains(requirement)));
            if (goalsRetained && requirementsRetained) return index;
        }
        return -1;
    }

    private List<String> boundedHistory(List<String> messages, int maximumTokens) {
        if (messages == null || messages.isEmpty() || maximumTokens < 1) return List.of();
        var reversed = new ArrayList<String>();
        int used = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            var message = messages.get(index);
            int tokens = AgenticV4ModelInvoker.estimatedTokens(message);
            if (used + tokens > maximumTokens) {
                if (reversed.isEmpty()) reversed.add(truncate(message, maximumTokens));
                break;
            }
            reversed.add(message);
            used += tokens;
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private String answerContext(RequestAnalysis analysis, List<AcceptedEvidence> evidence) {
        var goalIds = evidence.stream().map(AcceptedEvidence::goalId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var context = new StringBuilder("仅依据下列存在直接证据的子问题作答；不要补造事实，也不要主动列举缺失面：");
        analysis.goals().stream().filter(goal -> goalIds.contains(goal.id()))
                .forEach(goal -> context.append("\n- ").append(goal.question()));
        var constraints = analysis.answerConstraints().stream()
                .filter(value -> goalIds.containsAll(value.appliesToGoalIds())).toList();
        if (!constraints.isEmpty()) {
            context.append("\n回答约束：");
            constraints.forEach(value -> context.append("\n- ").append(value.description()));
        }
        return context.toString();
    }

    private List<AcceptedEvidence> fairEvidence(List<AcceptedEvidence> evidence, int maximum) {
        var ordered = evidence.stream().sorted(
                java.util.Comparator.comparingDouble(AcceptedEvidence::retrievalScore).reversed()).toList();
        var selected = new ArrayList<AcceptedEvidence>();
        var requirements = new java.util.LinkedHashSet<String>();
        for (var item : ordered) {
            boolean adds = item.activeRequirementIds().stream().map(id -> item.goalId() + ":" + id)
                    .anyMatch(key -> !requirements.contains(key));
            if (!adds || selected.size() >= maximum) continue;
            selected.add(item);
            item.activeRequirementIds().forEach(id -> requirements.add(item.goalId() + ":" + id));
        }
        ordered.stream().filter(value -> !selected.contains(value)).limit(maximum - selected.size())
                .forEach(selected::add);
        return List.copyOf(selected);
    }

    private int estimatedTokens(
            boolean completeRequest,
            String question,
            String context,
            List<NumberedEvidence> evidence,
            String systemInstruction,
            List<String> history
    ) {
        if (!completeRequest) {
            return AgenticV4ModelInvoker.estimatedTokens(question)
                    + AgenticV4ModelInvoker.estimatedTokens(context)
                    + evidence.stream().mapToInt(value -> AgenticV4ModelInvoker.estimatedTokens(
                            value.packed().evidence().quote()) + AgenticV4ModelInvoker.estimatedTokens(
                            value.packed().evidence().titlePath()) + 40).sum();
        }
        return AgenticV4ModelInvoker.estimatedTokens(question)
                + AgenticV4ModelInvoker.estimatedTokens(context)
                + AgenticV4ModelInvoker.estimatedTokens(systemInstruction)
                + history.stream().mapToInt(AgenticV4ModelInvoker::estimatedTokens).sum()
                + evidence.stream().mapToInt(value -> value.packed().estimatedTokens()).sum()
                + 256;
    }

    private String truncate(String value, int maximumTokens) {
        if (value == null || value.isBlank() || AgenticV4ModelInvoker.estimatedTokens(value) <= maximumTokens) {
            return value == null ? "" : value;
        }
        int low = 1, high = value.length(), best = 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            if (AgenticV4ModelInvoker.estimatedTokens(value.substring(0, middle)) <= maximumTokens) {
                best = middle;
                low = middle + 1;
            } else high = middle - 1;
        }
        return value.substring(0, best);
    }

    private RetrievalHit citationHit(AcceptedEvidence evidence) {
        var segments = evidence.sourceAnchor().segments();
        Integer pageNumber = segments.stream().map(value -> value.pageNumber())
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        Integer sourceStart = segments.stream().map(value -> value.documentSourceStart())
                .filter(java.util.Objects::nonNull).min(Integer::compareTo).orElse(null);
        Integer sourceEnd = segments.stream().map(value -> value.documentSourceEnd())
                .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(null);
        return new RetrievalHit(evidence.parentChunkId(), null, evidence.documentId(), evidence.documentVersionId(),
                evidence.titlePath(), evidence.quote(), evidence.retrievalScore(),
                evidence.retrievalSources().stream().map(value -> value.name().toLowerCase()).toList(),
                pageNumber, sourceStart, sourceEnd);
    }

    private int uniqueParentCount(List<AcceptedEvidence> evidence) {
        return (int) evidence.stream().map(value -> value.documentVersionId() + ":" + value.parentChunkId())
                .distinct().count();
    }

    private List<String> referencedEvidence(String answer) {
        var result = new java.util.LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern.compile("\\[E(\\d+)]").matcher(answer == null ? "" : answer);
        while (matcher.find()) result.add("E" + matcher.group(1));
        return List.copyOf(result);
    }

    private int remainingSeconds(AgentBudgetLedger ledger, int configuredMaximum) {
        long millis = java.time.Duration.between(clock.instant(), ledger.deadline()).minusSeconds(2).toMillis();
        if (millis <= 0) throw new IllegalStateException("Run Deadline 已耗尽");
        return Math.max(1, (int) Math.min(Math.max(5, Math.min(120, configuredMaximum)), (millis + 999) / 1_000));
    }

    private long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record NumberedEvidence(String id, FinalAnswerEvidencePackBuilder.PackedEvidence packed) {
    }
}
