package com.yanyue.rag.application.chat.v5;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;
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
public class AgenticRagV5Pipeline {
    public static final String PIPELINE_VERSION = "agentic-rag-v5";
    public static final String NO_EVIDENCE_MESSAGE = "当前知识库中缺乏可用于回答该问题的相关依据。";

    private final PipelineConfigService pipelineConfigs;
    private final MetadataSchemaService metadataSchemas;
    private final ConversationMemoryPort memory;
    private final RequestAnalysisReasonerV5 requestAnalysis;
    private final GoalResearchServiceV5 research;
    private final EvidenceJudgeReasonerV5 judge;
    private final CoverageStateReducerV5 coverageReducer;
    private final AgentAnswerServiceV5 answerService;
    private final AgenticV4ArtifactPort artifacts;
    private final RunRecordPort runRecords;
    private final RunEventHub events;
    private final Executor executor;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AgenticRagV5Pipeline(
            PipelineConfigService pipelineConfigs,
            MetadataSchemaService metadataSchemas,
            ConversationMemoryPort memory,
            RequestAnalysisReasonerV5 requestAnalysis,
            GoalResearchServiceV5 research,
            EvidenceJudgeReasonerV5 judge,
            CoverageStateReducerV5 coverageReducer,
            AgentAnswerServiceV5 answerService,
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
        this.answerService = answerService;
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
        var config = pipelineConfigs.resolve(organizationId, request.modelProfileId());
        var profileId = request.modelProfileId() == null ? config.chatProfileId() : request.modelProfileId();
        var limits = AgenticV5Limits.defaults();
        runRecords.applyAgentV5Runtime(runId, config, profileId, asMap(limits));
        var ledger = new AgentBudgetLedger(limits, clock.instant());
        var question = request.query().strip().replaceAll("\\s+", " ");
        checkCancelled(runId);
        checkpoint(runId, "ANALYZING", Map.of("pipelineVersion", PIPELINE_VERSION,
                "checkpointVersion", 4, "limitsVersion", AgenticV5Limits.VERSION,
                "effectiveLimits", limits, "budget", ledger.snapshot()));

        reserveStage(ledger, "stage:request-analysis");
        var recent = memory.recentMessages(conversationId, Math.min(6, config.recentTurns()));
        var analysis = requestAnalysis.analyze(profileId, runId, question, recent, ledger, limits);
        events.publish(runId, StreamEventType.PLAN_CREATED, analysis);
        checkpoint(runId, "PRIMARY_RESEARCH", Map.of("analysis", analysis, "budget", ledger.snapshot()));

        var filters = metadataSchemas.validateFilters(
                organizationId, request.scope().knowledgeBaseIds(), request.filters());
        var scope = RetrievalScope.forUser(organizationId, userId, request.scope().knowledgeBaseIds(),
                request.scope().documentIds(), filters, clock.instant());
        var pool = new GoalEvidencePool(analysis);
        reserveStage(ledger, "stage:primary-research");
        var allResults = new ArrayList<>(researchGoals(runId, profileId, config.rerankProfileId(), analysis,
                scope, ledger, limits, pool, null));

        checkCancelled(runId);
        checkpoint(runId, "JUDGING", Map.of("analysis", analysis, "evidenceCount", pool.size(),
                "budget", ledger.snapshot()));
        reserveStage(ledger, "stage:evidence-judge");
        events.publish(runId, StreamEventType.EVIDENCE_JUDGE_STARTED,
                Map.of("goalCount", analysis.goals().size(), "evidenceCount", pool.size()));
        var decision = judge.judge(profileId, runId, analysis, pool, ledger, limits);
        var coverage = coverageReducer.fromJudge(decision);
        artifacts.saveJudgeDecision(runId, coverage.goalStatuses().values().stream()
                .allMatch(value -> value == GoalStatus.SATISFIED_LOCKED), decision.degraded(), asMap(decision));
        events.publish(runId, StreamEventType.EVIDENCE_JUDGE_COMPLETED,
                Map.of("degraded", decision.degraded(), "goalStatuses", coverage.goalStatuses()));

        var repairDecisions = decision.goals().stream()
                .filter(value -> value.status() == GoalStatus.NEEDS_REPAIR && value.repairQueryPair() != null).toList();
        if (!repairDecisions.isEmpty()) {
            checkCancelled(runId);
            reserveStage(ledger, "stage:repair-research");
            checkpoint(runId, "REPAIR_RESEARCH", Map.of("analysis", analysis, "decision", decision,
                    "coverage", coverage, "repairGoalCount", repairDecisions.size(), "budget", ledger.snapshot()));
            var repairResults = researchGoals(runId, profileId, config.rerankProfileId(), analysis,
                    scope, ledger, limits, pool, decision);
            allResults.addAll(repairResults);
            coverage = coverage.afterRepair();
        }
        return finish(runId, conversationId, organizationId, userId, question, profileId,
                config.llmTimeoutSeconds(), generateAnswer, analysis, pool, allResults, coverage, ledger, limits);
    }

    private List<GoalResearchServiceV5.ResearchResult> researchGoals(
            UUID runId,
            UUID profileId,
            UUID rerankProfileId,
            RequestAnalysis analysis,
            RetrievalScope scope,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            GoalEvidencePool pool,
            EvidenceJudgeReasonerV5.JudgeDecision decision
    ) {
        var futures = new ArrayList<CompletableFuture<GoalResearchServiceV5.ResearchResult>>();
        for (int index = 0; index < analysis.goals().size(); index++) {
            var goal = analysis.goals().get(index);
            var pair = decision == null ? goal.primaryQueryPair() : repairPairFor(decision, goal.id());
            if (pair == null) continue;
            var phase = pair.phase();
            int goalOrder = index + 1;
            events.publish(runId, StreamEventType.GOAL_RESEARCH_STARTED,
                    Map.of("goalId", goal.id(), "phase", phase, "queryCount", 2));
            futures.add(CompletableFuture.supplyAsync(() -> research.research(profileId, rerankProfileId,
                    runId, goalOrder, analysis.standaloneObjective(), goal, pair, scope, ledger, limits, pool),
                    executor).thenApply(result -> {
                        checkCancelled(runId);
                        persistResearch(runId, result);
                        return result;
                    }));
        }
        return await(futures, ledger);
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

    private void persistResearch(UUID runId, GoalResearchServiceV5.ResearchResult result) {
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
            boolean generateAnswer,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            List<GoalResearchServiceV5.ResearchResult> results,
            CoverageState coverage,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits
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
            if (results.stream().anyMatch(value -> value.health().mayHideEvidence())) {
                throw new IllegalStateException("研究链路未正常收敛，不能将零证据解释为知识库缺少依据");
            }
            runRecords.markNoAnswer(runId, "zero-accepted-evidence");
            runRecords.markAnswerMode(runId, "NO_EVIDENCE", "ZERO_ACCEPTED_EVIDENCE");
            events.publish(runId, StreamEventType.ANSWER_MODE_SELECTED,
                    Map.of("mode", "NO_EVIDENCE", "evidenceCount", 0));
            events.publish(runId, StreamEventType.NO_ANSWER, Map.of("reason", "zero-accepted-evidence"));
            events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", NO_EVIDENCE_MESSAGE));
            memory.append(conversationId, "user", question, runId);
            memory.append(conversationId, "assistant", NO_EVIDENCE_MESSAGE, runId);
            checkpoint(runId, "COMPLETED", Map.of("answerMode", "NO_EVIDENCE", "evidenceCount", 0,
                    "analysis", analysis, "coverage", coverage, "budget", ledger.snapshot()));
            return NO_EVIDENCE_MESSAGE;
        }
        reserveStage(ledger, "stage:final-answer");
        var answer = answerService.answer(runId, conversationId, organizationId, userId, question,
                analysis, profileId, timeoutSeconds, pool.all(), ledger, limits);
        runRecords.markAnswerMode(runId, "ANSWER_WITH_EVIDENCE", "COMPLETED_WITH_EVIDENCE");
        checkpoint(runId, "COMPLETED", Map.of("answerMode", "ANSWER_WITH_EVIDENCE",
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

    private void reserveStage(AgentBudgetLedger ledger, String actionKey) {
        var reservation = ledger.reserve(actionKey,
                Map.of(BudgetDimension.SERIAL_SEMANTIC_STAGE, 1L), clock.instant());
        ledger.markDispatched(reservation.reservationId(), clock.instant());
        ledger.succeed(reservation.reservationId(), Map.of(), clock.instant());
    }

    private void checkpoint(UUID runId, String stage, Map<String, Object> state) {
        artifacts.checkpoint(runId, stage, state);
    }

    private Map<String, Object> asMap(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }

    private void checkCancelled(UUID runId) {
        if (Thread.currentThread().isInterrupted() || runRecords.isCancellationRequested(runId)) {
            throw new CancellationException("Agentic RAG v5 已取消");
        }
    }
}
