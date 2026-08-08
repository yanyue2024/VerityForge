package com.yanyue.rag.application.chat.v7;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.application.chat.v5.AgentAnswerServiceV5;
import com.yanyue.rag.application.chat.v5.CoverageStateReducerV5;
import com.yanyue.rag.application.chat.v5.EvidenceJudgeReasonerV5;
import com.yanyue.rag.application.chat.v5.RequestAnalysisReasonerV5;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.application.pipeline.AssistantProfileService;
import com.yanyue.rag.application.chat.v8.ConversationalAnswerService;
import com.yanyue.rag.application.chat.v8.KnowledgeDemandClassifier;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;
import com.yanyue.rag.domain.agent.v7.AgenticV7Limits;
import com.yanyue.rag.domain.agent.v5.CoverageState;
import com.yanyue.rag.domain.agent.v5.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v5.GoalStatus;
import com.yanyue.rag.domain.agent.v5.RequestAnalysis;
import com.yanyue.rag.domain.port.AgenticV4ArtifactPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.RunRecordPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AgenticRagV7Pipeline {
    public static final String PIPELINE_VERSION = "agentic-rag-v7";
    public static final String NO_EVIDENCE_MESSAGE = "当前知识库中缺乏可用于回答该问题的相关依据。";

    private final PipelineConfigService pipelineConfigs;
    private final MetadataSchemaService metadataSchemas;
    private final ConversationMemoryPort memory;
    private final RequestAnalysisReasonerV5 requestAnalysis;
    private final GoalResearchServiceV7 research;
    private final EvidenceJudgeReasonerV5 judge;
    private final CoverageStateReducerV5 coverageReducer;
    private final GapActionReducerV7 gapActions;
    private final AgentAnswerServiceV5 answerService;
    private final ConversationalAnswerService conversationalAnswers;
    private final KnowledgeDemandClassifier knowledgeDemand;
    private final AssistantProfileService assistantProfiles;
    private final AgenticV4ArtifactPort artifacts;
    private final RunRecordPort runRecords;
    private final RunEventHub events;
    private final Executor executor;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AgenticRagV7Pipeline(
            PipelineConfigService pipelineConfigs,
            MetadataSchemaService metadataSchemas,
            ConversationMemoryPort memory,
            RequestAnalysisReasonerV5 requestAnalysis,
            GoalResearchServiceV7 research,
            EvidenceJudgeReasonerV5 judge,
            CoverageStateReducerV5 coverageReducer,
            GapActionReducerV7 gapActions,
            AgentAnswerServiceV5 answerService,
            ConversationalAnswerService conversationalAnswers,
            KnowledgeDemandClassifier knowledgeDemand,
            AssistantProfileService assistantProfiles,
            AgenticV4ArtifactPort artifacts,
            RunRecordPort runRecords,
            RunEventHub events,
            @Qualifier("ragRunExecutor") Executor executor,
            Clock clock,
            ObjectMapper objectMapper
    ) {
        this.pipelineConfigs = pipelineConfigs;
        this.metadataSchemas = metadataSchemas;
        this.memory = memory;
        this.requestAnalysis = requestAnalysis;
        this.research = research;
        this.judge = judge;
        this.coverageReducer = coverageReducer;
        this.gapActions = gapActions;
        this.answerService = answerService;
        this.conversationalAnswers = conversationalAnswers;
        this.knowledgeDemand = knowledgeDemand;
        this.assistantProfiles = assistantProfiles;
        this.artifacts = artifacts;
        this.runRecords = runRecords;
        this.events = events;
        this.executor = executor;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public String execute(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            CreateRunRequest request,
            boolean generateAnswer
    ) {
        var config = pipelineConfigs.resolve(organizationId, request.modelProfileId(),
                runRecords.pipelineConfigId(runId).orElse(null));
        var profileId = request.modelProfileId() == null ? config.chatProfileId() : request.modelProfileId();
        var assistant = assistantProfiles.forConversation(organizationId, conversationId);
        var limits = limits();
        applyRuntime(runId, config, profileId, limits);
        runRecords.applyAssistantProfile(runId, assistant.id());
        var ledger = new AgentBudgetLedger(limits, clock.instant());
        var question = request.query().strip().replaceAll("\\s+", " ");
        checkCancelled(runId);
        var recent = memory.recentMessages(conversationId, Math.min(6, config.recentTurns()));
        var initialDemand = knowledgeDemand.classify(question);
        if (generateAnswer && initialDemand == ConversationalAnswerService.KnowledgeDemand.NONE) {
            runRecords.markRetrievalHealth(runId, "EMPTY", 0);
            var result = conversationalAnswers.answer(runId, conversationId, question, question,
                    profileId, assistant, recent, config.temperature(), config.llmTimeoutSeconds(),
                    ledger, limits, initialDemand, ConversationalAnswerService.RetrievalHealth.EMPTY);
            runRecords.markAnswerMode(runId, result.answerMode(), "COMPLETED_WITHOUT_EVIDENCE");
            checkpoint(runId, "COMPLETED", Map.of("answerMode", result.answerMode(),
                    "retrievalHealth", "EMPTY", "evidenceCount", 0, "budget", ledger.snapshot()));
            return result.answer();
        }
        checkpoint(runId, "ANALYZING", Map.of("pipelineVersion", pipelineVersion(),
                "checkpointVersion", 3, "limitsVersion", limitsVersion(),
                "effectiveLimits", limits, "budget", ledger.snapshot()));

        reserveStage(ledger, "stage:request-analysis");
        var analysis = analyzeRequest(profileId, runId, question, recent, ledger, limits);
        events.publish(runId, StreamEventType.PLAN_CREATED, analysis);
        checkpoint(runId, "PRIMARY_RESEARCH", Map.of("analysis", analysis, "budget", ledger.snapshot()));

        var filters = metadataSchemas.validateFilters(
                organizationId, request.scope().knowledgeBaseIds(), request.filters());
        var scope = RetrievalScope.forUser(organizationId, userId, request.scope().knowledgeBaseIds(),
                request.scope().documentIds(), filters, clock.instant());
        var pool = new GoalEvidencePool(analysis, limits);
        reserveStage(ledger, "stage:primary-research");
        var allResults = new ArrayList<>(researchGoals(runId, profileId, config.rerankProfileId(), analysis,
                scope, ledger, limits, pool, null, null));

        checkCancelled(runId);
        checkpoint(runId, "JUDGING", Map.of("analysis", analysis, "evidenceCount", pool.size(),
                "budget", ledger.snapshot()));
        reserveStage(ledger, "stage:evidence-judge");
        events.publish(runId, StreamEventType.EVIDENCE_JUDGE_STARTED,
                Map.of("goalCount", analysis.goals().size(), "evidenceCount", pool.size()));
        var decision = judgeEvidence(profileId, runId, analysis, pool, ledger, limits);
        var coverage = coverageReducer.fromJudge(decision);
        var actions = gapActions.reduce(analysis, decision, pool);
        var judgeReport = new java.util.LinkedHashMap<String, Object>(asMap(decision));
        judgeReport.put("gapActions", actions);
        artifacts.saveJudgeDecision(runId, coverage.goalStatuses().values().stream()
                .allMatch(value -> value == GoalStatus.SATISFIED_LOCKED), decision.degraded(), judgeReport);
        events.publish(runId, StreamEventType.EVIDENCE_JUDGE_COMPLETED,
                Map.of("degraded", decision.degraded(), "goalStatuses", coverage.goalStatuses()));
        actions.forEach(action -> events.publish(runId, StreamEventType.GAP_IDENTIFIED,
                Map.of("goalId", action.goalId(), "action", action.type(),
                        "missingRequirementIds", action.missingRequirementIds())));

        var readMoreActions = actions.stream()
                .filter(value -> value.type() == GapActionReducerV7.Type.READ_MORE).toList();
        var optionalDeadline = optionalWorkDeadline(ledger, config.llmTimeoutSeconds());
        var readMoreResults = canRunOptionalWork(optionalDeadline) ? readMoreActions.stream()
                .map(action -> CompletableFuture.supplyAsync(() -> {
                    var goal = analysis.goals().stream().filter(value -> value.id().equals(action.goalId()))
                            .findFirst().orElseThrow();
                    return research.readMore(profileId, runId, analysis.standaloneObjective(), goal,
                            action.missingRequirementIds(), scope, ledger, limits, pool, optionalDeadline);
                }, executor)).toList() : List.<CompletableFuture<GoalResearchServiceV7.ResearchResult>>of();
        var localResults = awaitBestEffort(readMoreResults, ledger, optionalDeadline);
        localResults.forEach(result -> {
            allResults.add(result);
            persistResearch(runId, result);
        });
        var localEvidenceGoals = localResults.stream().filter(value -> !value.acceptedEvidence().isEmpty())
                .map(GoalResearchServiceV7.ResearchResult::goalId).collect(java.util.stream.Collectors.toSet());
        var repairDecisions = decision.goals().stream()
                .filter(value -> value.status() == GoalStatus.NEEDS_REPAIR && value.repairQueryPair() != null)
                .filter(value -> !localEvidenceGoals.contains(value.goalId())).toList();
        if (!repairDecisions.isEmpty() && canRunOptionalWork(optionalDeadline)) {
            checkCancelled(runId);
            try {
                reserveStage(ledger, "stage:repair-research");
                checkpoint(runId, "REPAIR_RESEARCH", Map.of("analysis", analysis, "decision", decision,
                        "coverage", coverage, "repairGoalCount", repairDecisions.size(),
                        "answerBudgetReserved", true, "budget", ledger.snapshot()));
                var searchGoalIds = repairDecisions.stream()
                        .map(EvidenceJudgeReasonerV5.GoalDecision::goalId)
                        .collect(java.util.stream.Collectors.toSet());
                var repairResults = researchGoals(runId, profileId, config.rerankProfileId(), analysis,
                        scope, ledger, limits, pool, decision, searchGoalIds, optionalDeadline, true);
                allResults.addAll(repairResults);
                coverage = coverage.afterRepair();
            } catch (CancellationException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                publishOptionalWorkSkipped(runId, repairDecisions, "REPAIR_DEGRADED");
            }
        } else if (!repairDecisions.isEmpty()) {
            publishOptionalWorkSkipped(runId, repairDecisions, "ANSWER_BUDGET_RESERVED");
        }
        research.clearSnapshots(runId);
        return finish(runId, conversationId, organizationId, userId, question, profileId,
                config.llmTimeoutSeconds(), config.temperature(), config.recentTurns(), generateAnswer,
                analysis, pool, allResults, coverage, ledger, limits, assistant);
    }

    private List<GoalResearchServiceV7.ResearchResult> researchGoals(
            UUID runId,
            UUID profileId,
            UUID rerankProfileId,
            RequestAnalysis analysis,
            RetrievalScope scope,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            GoalEvidencePool pool,
            EvidenceJudgeReasonerV5.JudgeDecision decision,
            java.util.Set<UUID> onlyGoalIds
    ) {
        return researchGoals(runId, profileId, rerankProfileId, analysis, scope, ledger, limits, pool,
                decision, onlyGoalIds, ledger.deadline(), false);
    }

    private List<GoalResearchServiceV7.ResearchResult> researchGoals(
            UUID runId,
            UUID profileId,
            UUID rerankProfileId,
            RequestAnalysis analysis,
            RetrievalScope scope,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            GoalEvidencePool pool,
            EvidenceJudgeReasonerV5.JudgeDecision decision,
            java.util.Set<UUID> onlyGoalIds,
            java.time.Instant operationDeadline,
            boolean bestEffort
    ) {
        var futures = new ArrayList<CompletableFuture<GoalResearchServiceV7.ResearchResult>>();
        for (int index = 0; index < analysis.goals().size(); index++) {
            var goal = analysis.goals().get(index);
            if (onlyGoalIds != null && !onlyGoalIds.contains(goal.id())) continue;
            var pair = decision == null ? goal.primaryQueryPair() : repairPairFor(decision, goal.id());
            if (pair == null) continue;
            var phase = pair.phase();
            int goalOrder = index + 1;
            events.publish(runId, StreamEventType.GOAL_RESEARCH_STARTED,
                    Map.of("goalId", goal.id(), "phase", phase, "queryCount", 2));
            futures.add(CompletableFuture.supplyAsync(() -> research.research(profileId, rerankProfileId,
                    runId, goalOrder, analysis.standaloneObjective(), goal, pair, scope, ledger, limits, pool,
                    operationDeadline),
                    executor).thenApply(result -> {
                        checkCancelled(runId);
                        persistResearch(runId, result);
                        return result;
                    }));
        }
        return bestEffort ? awaitBestEffort(futures, ledger, operationDeadline) : await(futures, ledger);
    }

    static com.yanyue.rag.domain.agent.v5.QueryPair repairPairFor(
            EvidenceJudgeReasonerV5.JudgeDecision decision,
            UUID goalId
    ) {
        return decision.goals().stream()
                .filter(value -> value.goalId().equals(goalId))
                .filter(value -> value.status() == GoalStatus.NEEDS_REPAIR)
                .findFirst()
                .map(EvidenceJudgeReasonerV5.GoalDecision::repairQueryPair)
                .orElse(null);
    }

    private void persistResearch(UUID runId, GoalResearchServiceV7.ResearchResult result) {
        result.acceptedEvidence().forEach(value -> artifacts.saveEvidence(runId, value));
        artifacts.saveGoalOutcome(runId, result.goalId(), result.phase(), result.health(), result.searchTaskIds(),
                result.deepReadLogicalCallId(),
                result.acceptedEvidence().stream().map(AcceptedEvidence::evidenceId).toList(),
                result.mayHaveHiddenEvidence());
        var type = result.health().mayHideEvidence()
                ? StreamEventType.GOAL_RESEARCH_FAILED : StreamEventType.GOAL_RESEARCH_COMPLETED;
        events.publish(runId, type, Map.of("goalId", result.goalId(), "phase", result.phase(),
                "health", result.health(), "acceptedEvidenceCount", result.acceptedEvidence().size()));
    }

    private String finish(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            String question,
            UUID profileId,
            int timeoutSeconds,
            double temperature,
            int recentTurns,
            boolean generateAnswer,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            List<GoalResearchServiceV7.ResearchResult> results,
            CoverageState coverage,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            com.yanyue.rag.domain.model.AssistantProfile assistant
    ) {
        checkCancelled(runId);
        checkpoint(runId, "FINALIZING", Map.of("analysis", analysis, "coverage", coverage,
                "evidenceCount", pool.size(), "budget", ledger.snapshot()));
        if (!generateAnswer) {
            checkpoint(runId, "COMPLETED", Map.of("answerGenerationSkipped", true,
                    "evidenceCount", pool.size(), "analysis", analysis, "coverage", coverage,
                    "budget", ledger.snapshot()));
            return "";
        }
        if (pool.size() == 0) {
            var demand = knowledgeDemand.classify(question);
            var retrievalHealth = demand == ConversationalAnswerService.KnowledgeDemand.ORGANIZATION_SPECIFIC
                    && results.stream().anyMatch(value -> value.health().mayHideEvidence())
                    ? ConversationalAnswerService.RetrievalHealth.DEGRADED
                    : ConversationalAnswerService.RetrievalHealth.EMPTY;
            runRecords.markNoAnswer(runId, "zero-accepted-evidence");
            runRecords.markRetrievalHealth(runId, retrievalHealth.name(), 0);
            var result = conversationalAnswers.answer(runId, conversationId, question,
                    analysis.standaloneObjective(), profileId, assistant,
                    memory.recentMessages(conversationId, recentTurns), temperature, timeoutSeconds,
                    ledger, limits, demand, retrievalHealth);
            runRecords.markAnswerMode(runId, result.answerMode(), "COMPLETED_WITHOUT_EVIDENCE");
            checkpoint(runId, "COMPLETED", Map.of("answerMode", result.answerMode(),
                    "retrievalHealth", retrievalHealth, "evidenceCount", 0,
                    "analysis", analysis, "coverage", coverage, "budget", ledger.snapshot()));
            return result.answer();
        }
        reserveStage(ledger, "stage:final-answer");
        var retrievalHealth = coverage.goalStatuses().values().stream()
                .allMatch(value -> value == GoalStatus.SATISFIED_LOCKED)
                ? "SUFFICIENT" : "PARTIAL";
        runRecords.markRetrievalHealth(runId, retrievalHealth, pool.size());
        var answer = answerService.answer(runId, conversationId, organizationId, userId, question,
                analysis, profileId, timeoutSeconds, pool.all(), ledger, limits, assistant,
                memory.recentMessages(conversationId, recentTurns), temperature);
        var answerMode = "SUFFICIENT".equals(retrievalHealth) ? "GROUNDED" : "PARTIAL_GROUNDED";
        runRecords.markAnswerMode(runId, answerMode, "COMPLETED_WITH_EVIDENCE");
        events.publish(runId, StreamEventType.ANSWER_MODE_SELECTED,
                Map.of("mode", answerMode, "evidenceCount", pool.size(),
                        "retrievalHealth", retrievalHealth));
        checkpoint(runId, "COMPLETED", Map.of("answerMode", answerMode,
                "retrievalHealth", retrievalHealth,
                "evidenceCount", pool.size(), "analysis", analysis, "coverage", coverage,
                "budget", ledger.snapshot()));
        return answer;
    }

    private <T> List<T> await(List<CompletableFuture<T>> futures, AgentBudgetLedger ledger) {
        try {
            long nanos = java.time.Duration.between(clock.instant(), ledger.deadline()).minusSeconds(2).toNanos();
            if (nanos <= 0) throw new IllegalStateException("Run Deadline 已耗尽");
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(
                    nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            return futures.stream().map(CompletableFuture::join).toList();
        } catch (java.util.concurrent.TimeoutException failure) {
            futures.forEach(value -> value.cancel(true));
            throw new IllegalStateException("Goal 研究汇合超过 Run Deadline", failure);
        } catch (InterruptedException failure) {
            futures.forEach(value -> value.cancel(true));
            Thread.currentThread().interrupt();
            throw new CancellationException("Goal 研究汇合被取消");
        } catch (java.util.concurrent.ExecutionException failure) {
            var cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Goal 研究执行失败", cause);
        }
    }

    private <T> List<T> awaitBestEffort(
            List<CompletableFuture<T>> futures,
            AgentBudgetLedger ledger,
            java.time.Instant operationDeadline
    ) {
        if (futures.isEmpty()) return List.of();
        try {
            var effectiveDeadline = operationDeadline.isAfter(ledger.deadline())
                    ? ledger.deadline() : operationDeadline;
            long nanos = java.time.Duration.between(clock.instant(), effectiveDeadline).minusSeconds(2).toNanos();
            if (nanos > 0) {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(
                        nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException ignored) {
            futures.stream().filter(value -> !value.isDone()).forEach(value -> value.cancel(true));
        } catch (InterruptedException failure) {
            futures.forEach(value -> value.cancel(true));
            Thread.currentThread().interrupt();
            throw new CancellationException("可选研究阶段被取消");
        }
        return futures.stream()
                .filter(CompletableFuture::isDone)
                .filter(value -> !value.isCancelled() && !value.isCompletedExceptionally())
                .map(CompletableFuture::join)
                .toList();
    }

    static java.time.Instant optionalWorkDeadline(AgentBudgetLedger ledger, int configuredAnswerTimeoutSeconds) {
        int answerSeconds = Math.max(30, Math.min(120, configuredAnswerTimeoutSeconds));
        return ledger.deadline().minusSeconds(answerSeconds + 3L);
    }

    private boolean canRunOptionalWork(java.time.Instant optionalDeadline) {
        return clock.instant().plusSeconds(2).isBefore(optionalDeadline);
    }

    private void publishOptionalWorkSkipped(
            UUID runId,
            List<EvidenceJudgeReasonerV5.GoalDecision> decisions,
            String reason
    ) {
        decisions.forEach(value -> events.publish(runId, StreamEventType.GOAL_RESEARCH_FAILED,
                Map.of("goalId", value.goalId(), "phase", ResearchPhase.REPAIR,
                        "health", "DEGRADED_NON_BLOCKING", "reason", reason,
                        "acceptedEvidenceCount", 0)));
    }

    private void reserveStage(AgentBudgetLedger ledger, String actionKey) {
        var reservation = ledger.reserve(actionKey,
                Map.of(BudgetDimension.SERIAL_SEMANTIC_STAGE, 1L), clock.instant());
        ledger.markDispatched(reservation.reservationId(), clock.instant());
        ledger.succeed(reservation.reservationId(), Map.of(), clock.instant());
    }

    private void checkpoint(UUID runId, String stage, Map<String, Object> state) {
        artifacts.checkpoint(runId, stage, state);
    }

    protected Map<String, Object> asMap(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }

    /** Extension points used by the versioned v8 facade while preserving v7 defaults. */
    protected String pipelineVersion() {
        return PIPELINE_VERSION;
    }

    protected String limitsVersion() {
        return AgenticV7Limits.VERSION;
    }

    protected AgenticV5Limits limits() {
        return AgenticV7Limits.defaults();
    }

    protected RequestAnalysis analyzeRequest(
            UUID profileId,
            UUID runId,
            String question,
            List<String> recentMessages,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits
    ) {
        return requestAnalysis.analyzeV7(profileId, runId, question, recentMessages, ledger, limits);
    }

    protected EvidenceJudgeReasonerV5.JudgeDecision judgeEvidence(
            UUID profileId,
            UUID runId,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits
    ) {
        return judge.judgeV7(profileId, runId, analysis, pool, ledger, limits);
    }

    protected RequestAnalysisReasonerV5 requestAnalysis() {
        return requestAnalysis;
    }

    protected EvidenceJudgeReasonerV5 judgeReasoner() {
        return judge;
    }

    protected void applyRuntime(UUID runId, com.yanyue.rag.domain.model.PipelineConfig config,
                                UUID profileId, AgenticV5Limits limits) {
        runRecords.applyAgentV7Runtime(runId, config, profileId, asMap(limits));
    }

    private void checkCancelled(UUID runId) {
        if (Thread.currentThread().isInterrupted() || runRecords.isCancellationRequested(runId)) {
            throw new CancellationException("Agentic RAG v5 已取消");
        }
    }
}
