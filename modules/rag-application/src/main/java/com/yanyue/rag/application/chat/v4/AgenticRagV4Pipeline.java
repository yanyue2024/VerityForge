package com.yanyue.rag.application.chat.v4;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.AgenticV4Limits;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v4.GoalStatus;
import com.yanyue.rag.domain.agent.v4.ResearchHealth;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.port.AgenticV4ArtifactPort;
import com.yanyue.rag.domain.port.AgenticV4EvidenceValidationPort;
import com.yanyue.rag.domain.port.AgenticV4RecoveryPort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.CitationValidationPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RunRecordPort;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AgenticRagV4Pipeline {
    public static final String PIPELINE_VERSION = "agentic-rag-v4";
    public static final String NO_EVIDENCE_MESSAGE = "当前知识库中缺乏可用于回答该问题的相关依据。";

    private final PipelineConfigService pipelineConfigs;
    private final MetadataSchemaService metadataSchemas;
    private final ConversationMemoryPort memory;
    private final RequestAnalysisReasoner requestAnalysis;
    private final GoalResearchService research;
    private final EvidenceJudgeReasoner judge;
    private final CoverageStateReducer coverageReducer;
    private final StreamingAnswerModelPort answerModel;
    private final CitationPort citations;
    private final CitationValidationPort citationValidation;
    private final AgenticV4EvidenceValidationPort evidenceValidation;
    private final AgenticV4ArtifactPort artifacts;
    private final RunRecordPort runRecords;
    private final RunEventHub events;
    private final Executor executor;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AgenticRagV4Pipeline(
            PipelineConfigService pipelineConfigs,
            MetadataSchemaService metadataSchemas,
            ConversationMemoryPort memory,
            RequestAnalysisReasoner requestAnalysis,
            GoalResearchService research,
            EvidenceJudgeReasoner judge,
            CoverageStateReducer coverageReducer,
            StreamingAnswerModelPort answerModel,
            CitationPort citations,
            CitationValidationPort citationValidation,
            AgenticV4EvidenceValidationPort evidenceValidation,
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
        this.answerModel = answerModel;
        this.citations = citations;
        this.citationValidation = citationValidation;
        this.evidenceValidation = evidenceValidation;
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
        runRecords.applyAgentV4Runtime(runId, config, profileId);
        var limits = AgenticV4Limits.defaults();
        var ledger = new AgentBudgetLedger(limits, clock.instant());
        var normalizedQuestion = request.query().strip().replaceAll("\\s+", " ");
        checkCancelled(runId);
        checkpoint(runId, "PLAN", Map.of("pipelineVersion", PIPELINE_VERSION,
                "checkpointVersion", 3, "budget", ledger.snapshot()));

        reserveStage(ledger, "stage:request-analysis");
        var recent = memory.recentMessages(conversationId, Math.min(3, config.recentTurns()));
        var analysis = requestAnalysis.analyze(profileId, runId, normalizedQuestion, recent, ledger);
        events.publish(runId, StreamEventType.PLAN_CREATED, analysis);
        checkpoint(runId, "PRIMARY_RESEARCH", Map.of("analysis", analysis, "budget", ledger.snapshot()));

        var validatedFilters = metadataSchemas.validateFilters(
                organizationId, request.scope().knowledgeBaseIds(), request.filters());
        var scope = RetrievalScope.forUser(organizationId, userId, request.scope().knowledgeBaseIds(),
                request.scope().documentIds(), validatedFilters, clock.instant());
        var pool = new GoalEvidencePool(analysis);
        reserveStage(ledger, "stage:primary-research");
        var primaryResults = researchGoals(runId, profileId, config.rerankProfileId(), analysis, scope,
                ResearchPhase.PRIMARY, null, config.keywordTopK(), config.semanticTopK(),
                config.rerankCandidateLimit(), config.minimumRerankScore(), ledger, pool,
                analysis.goals().stream().map(value -> value.id()).collect(java.util.stream.Collectors.toSet()));
        var allResearchResults = new ArrayList<>(primaryResults);

        checkCancelled(runId);
        checkpoint(runId, "COVERAGE_JUDGE", Map.of("analysis", analysis,
                "evidenceCount", pool.size(), "budget", ledger.snapshot()));
        reserveStage(ledger, "stage:evidence-judge");
        events.publish(runId, StreamEventType.EVIDENCE_JUDGE_STARTED,
                Map.of("goalCount", analysis.goals().size(), "evidenceCount", pool.size()));
        var decision = judge.judge(profileId, runId, analysis, pool, ledger);
        var coverage = coverageReducer.fromJudge(decision);
        artifacts.saveJudgeDecision(runId,
                coverage.goalStatuses().values().stream().allMatch(value -> value == GoalStatus.SATISFIED_LOCKED),
                decision.degraded(), asMap(decision));
        events.publish(runId, StreamEventType.EVIDENCE_JUDGE_COMPLETED,
                Map.of("degraded", decision.degraded(), "goalStatuses", coverage.goalStatuses()));

        var repairable = decision.goals().stream()
                .filter(value -> value.goalStatus() != GoalStatus.SATISFIED_LOCKED
                        && !value.repairQueries().isEmpty()).toList();
        if (!repairable.isEmpty()) {
            checkCancelled(runId);
            reserveStage(ledger, "stage:repair-research");
            checkpoint(runId, "REPAIR_RESEARCH", Map.of("analysis", analysis, "decision", decision,
                    "coverage", coverage, "repairGoalCount", repairable.size(), "budget", ledger.snapshot()));
            var repairResults = researchGoals(runId, profileId, config.rerankProfileId(), analysis, scope,
                    ResearchPhase.REPAIR, decision, config.keywordTopK(), config.semanticTopK(),
                    config.rerankCandidateLimit(), config.minimumRerankScore(), ledger, pool,
                    repairable.stream().map(value -> value.goalId()).collect(java.util.stream.Collectors.toSet()));
            allResearchResults.addAll(repairResults);
            var newEvidence = repairResults.stream().flatMap(value -> value.acceptedEvidence().stream()).toList();
            coverage = coverageReducer.afterRepair(coverage, decision, newEvidence);
        }

        return finish(new CompletionInput(runId, conversationId, organizationId, userId, normalizedQuestion,
                profileId, config.llmTimeoutSeconds(), generateAnswer, analysis, pool,
                List.copyOf(allResearchResults), coverage, ledger));
    }

    private String finish(CompletionInput input) {
        checkCancelled(input.runId());
        checkpoint(input.runId(), "SYNTHESIZE", Map.of("analysis", input.analysis(),
                "coverage", input.coverage(), "evidenceCount", input.pool().size(),
                "budget", input.ledger().snapshot()));
        if (!input.generateAnswer()) {
            checkpoint(input.runId(), "COMPLETED", Map.of("answerGenerationSkipped", true,
                    "evidenceCount", input.pool().size(), "analysis", input.analysis(),
                    "coverage", input.coverage(), "budget", input.ledger().snapshot()));
            return "";
        }
        if (input.pool().size() == 0) {
            boolean evidenceMayBeHidden = input.researchResults().stream()
                    .anyMatch(value -> value.health().mayHideEvidence());
            if (evidenceMayBeHidden) {
                throw new IllegalStateException("研究链路未正常收敛，不能将零证据解释为知识库缺少依据");
            }
            runRecords.markNoAnswer(input.runId(), "zero-accepted-evidence");
            runRecords.markAnswerMode(input.runId(), "NO_EVIDENCE", "ZERO_ACCEPTED_EVIDENCE");
            events.publish(input.runId(), StreamEventType.ANSWER_MODE_SELECTED,
                    Map.of("mode", "NO_EVIDENCE", "evidenceCount", 0));
            events.publish(input.runId(), StreamEventType.NO_ANSWER, Map.of("reason", "zero-accepted-evidence"));
            events.publish(input.runId(), StreamEventType.ANSWER_DELTA, Map.of("text", NO_EVIDENCE_MESSAGE));
            memory.append(input.conversationId(), "user", input.question(), input.runId());
            memory.append(input.conversationId(), "assistant", NO_EVIDENCE_MESSAGE, input.runId());
            checkpoint(input.runId(), "COMPLETED", Map.of("answerMode", "NO_EVIDENCE", "evidenceCount", 0,
                    "analysis", input.analysis(), "coverage", input.coverage(),
                    "budget", input.ledger().snapshot()));
            return NO_EVIDENCE_MESSAGE;
        }

        reserveStage(input.ledger(), "stage:final-answer");
        var answer = generateAnswer(input.runId(), input.conversationId(), input.organizationId(), input.userId(),
                input.question(), input.analysis(), input.coverage(), input.profileId(), input.timeoutSeconds(),
                input.pool().all(), input.ledger());
        runRecords.markAnswerMode(input.runId(), "ANSWER_WITH_EVIDENCE", "COMPLETED_WITH_EVIDENCE");
        checkpoint(input.runId(), "COMPLETED", Map.of("answerMode", "ANSWER_WITH_EVIDENCE",
                "evidenceCount", input.pool().size(), "analysis", input.analysis(),
                "coverage", input.coverage(), "budget", input.ledger().snapshot()));
        return answer;
    }

    public String resume(
            AgenticV4RecoveryPort.RecoverableRun run,
            AgenticV4RecoveryPort.RecoverySnapshot snapshot,
            boolean generateAnswer
    ) {
        if ("COMPLETED".equals(snapshot.stage())) return "";
        var config = pipelineConfigs.resolve(run.organizationId(), run.request().modelProfileId());
        var profileId = run.request().modelProfileId() == null ? config.chatProfileId() : run.request().modelProfileId();
        runRecords.applyAgentV4Runtime(run.runId(), config, profileId);
        var ledger = AgentBudgetLedger.restore(AgenticV4Limits.defaults(), snapshot.runStartedAt(),
                snapshot.reservations());
        var question = run.request().query().strip().replaceAll("\\s+", " ");
        checkCancelled(run.runId());

        com.yanyue.rag.domain.agent.v4.RequestAnalysis analysis;
        var persistedAnalysis = snapshot.checkpointState().get("analysis");
        if (persistedAnalysis == null) {
            if (hasNonReplayable(snapshot, "request-analysis")) {
                throw new IllegalStateException("Request Analysis 已派发但没有可验证计划，拒绝盲目重放");
            }
            reserveStage(ledger, "stage:request-analysis");
            var recent = memory.recentMessages(run.conversationId(), Math.min(3, config.recentTurns()));
            analysis = requestAnalysis.analyze(profileId, run.runId(), question, recent, ledger);
            checkpoint(run.runId(), "PRIMARY_RESEARCH", Map.of("analysis", analysis, "budget", ledger.snapshot()));
        } else {
            analysis = objectMapper.convertValue(persistedAnalysis,
                    com.yanyue.rag.domain.agent.v4.RequestAnalysis.class);
        }

        var validatedFilters = metadataSchemas.validateFilters(run.organizationId(),
                run.request().scope().knowledgeBaseIds(), run.request().filters());
        var scope = RetrievalScope.forUser(run.organizationId(), run.userId(),
                run.request().scope().knowledgeBaseIds(), run.request().scope().documentIds(),
                validatedFilters, clock.instant());
        var pool = new GoalEvidencePool(analysis);
        snapshot.evidence().forEach(pool::accept);
        var researchResults = recoveredResults(snapshot, pool);

        var primaryCompleted = snapshot.goalOutcomes().stream()
                .filter(value -> value.phase() == ResearchPhase.PRIMARY)
                .map(AgenticV4RecoveryPort.GoalOutcome::goalId)
                .collect(java.util.stream.Collectors.toSet());
        var missingPrimary = analysis.goals().stream().map(value -> value.id())
                .filter(value -> !primaryCompleted.contains(value)).collect(java.util.stream.Collectors.toSet());
        var replayablePrimary = new java.util.LinkedHashSet<UUID>();
        for (var goal : analysis.goals()) {
            if (!missingPrimary.contains(goal.id())) continue;
            if (hasNonReplayable(snapshot, "search:" + goal.initialQuery().queryId())
                    || hasNonReplayable(snapshot, "deep-read:PRIMARY:" + goal.id())) {
                var hidden = new GoalResearchService.ResearchResult(goal.id(), ResearchPhase.PRIMARY, List.of(),
                        ResearchHealth.EVIDENCE_MAY_BE_HIDDEN, true);
                persistResearch(run.runId(), List.of(hidden));
                researchResults.add(hidden);
            } else {
                replayablePrimary.add(goal.id());
            }
        }
        if (!replayablePrimary.isEmpty()) {
            reserveStage(ledger, "stage:primary-research");
            var resumed = researchGoals(run.runId(), profileId, config.rerankProfileId(), analysis, scope,
                    ResearchPhase.PRIMARY, null, config.keywordTopK(), config.semanticTopK(),
                    config.rerankCandidateLimit(), config.minimumRerankScore(), ledger, pool, replayablePrimary);
            researchResults.addAll(resumed);
        }

        EvidenceJudgeReasoner.JudgeDecision decision;
        if (snapshot.judgeReport().isEmpty()) {
            if (hasNonReplayable(snapshot, "evidence-judge")) {
                throw new IllegalStateException("Evidence Judge 已派发但没有可验证决策，拒绝盲目重放");
            }
            reserveStage(ledger, "stage:evidence-judge");
            decision = judge.judge(profileId, run.runId(), analysis, pool, ledger);
            var initialCoverage = coverageReducer.fromJudge(decision);
            artifacts.saveJudgeDecision(run.runId(), initialCoverage.goalStatuses().values().stream()
                    .allMatch(value -> value == GoalStatus.SATISFIED_LOCKED), decision.degraded(), asMap(decision));
        } else {
            decision = objectMapper.convertValue(snapshot.judgeReport(), EvidenceJudgeReasoner.JudgeDecision.class);
        }
        var coverage = coverageReducer.fromJudge(decision);

        var repairCompleted = snapshot.goalOutcomes().stream()
                .filter(value -> value.phase() == ResearchPhase.REPAIR)
                .map(AgenticV4RecoveryPort.GoalOutcome::goalId)
                .collect(java.util.stream.Collectors.toSet());
        var repairable = decision.goals().stream()
                .filter(value -> value.goalStatus() != GoalStatus.SATISFIED_LOCKED
                        && !value.repairQueries().isEmpty()).toList();
        var replayableRepair = new java.util.LinkedHashSet<UUID>();
        for (var goal : repairable) {
            if (repairCompleted.contains(goal.goalId())) continue;
            boolean alreadyDispatched = goal.repairQueries().stream()
                    .anyMatch(query -> hasNonReplayable(snapshot, "search:" + query.queryId()))
                    || hasNonReplayable(snapshot, "deep-read:REPAIR:" + goal.goalId());
            if (alreadyDispatched) {
                var hidden = new GoalResearchService.ResearchResult(goal.goalId(), ResearchPhase.REPAIR, List.of(),
                        ResearchHealth.EVIDENCE_MAY_BE_HIDDEN, true);
                persistResearch(run.runId(), List.of(hidden));
                researchResults.add(hidden);
            } else {
                replayableRepair.add(goal.goalId());
            }
        }
        if (!replayableRepair.isEmpty()) {
            reserveStage(ledger, "stage:repair-research");
            var resumed = researchGoals(run.runId(), profileId, config.rerankProfileId(), analysis, scope,
                    ResearchPhase.REPAIR, decision, config.keywordTopK(), config.semanticTopK(),
                    config.rerankCandidateLimit(), config.minimumRerankScore(), ledger, pool, replayableRepair);
            researchResults.addAll(resumed);
        }
        if (!repairable.isEmpty()) {
            var repairEvidence = pool.all().stream()
                    .filter(value -> value.firstAcceptedPhase() == ResearchPhase.REPAIR).toList();
            coverage = coverageReducer.afterRepair(coverage, decision, repairEvidence);
        }
        if (generateAnswer && hasNonReplayable(snapshot, "final-answer")) {
            throw new IllegalStateException("最终回答调用已派发但没有完成 Barrier，拒绝重复生成");
        }
        return finish(new CompletionInput(run.runId(), run.conversationId(), run.organizationId(), run.userId(),
                question, profileId, config.llmTimeoutSeconds(), generateAnswer, analysis, pool,
                researchResults, coverage, ledger));
    }

    private ArrayList<GoalResearchService.ResearchResult> recoveredResults(
            AgenticV4RecoveryPort.RecoverySnapshot snapshot,
            GoalEvidencePool pool
    ) {
        var results = new ArrayList<GoalResearchService.ResearchResult>();
        for (var outcome : snapshot.goalOutcomes()) {
            var ids = java.util.Set.copyOf(outcome.acceptedEvidenceIds());
            var evidence = pool.forGoal(outcome.goalId()).stream()
                    .filter(value -> ids.contains(value.evidenceId())).toList();
            results.add(new GoalResearchService.ResearchResult(outcome.goalId(), outcome.phase(), evidence,
                    outcome.health(), outcome.mayHaveHiddenEvidence()));
        }
        return results;
    }

    private boolean hasNonReplayable(AgenticV4RecoveryPort.RecoverySnapshot snapshot, String actionKeyPrefix) {
        return snapshot.nonReplayableActionKeys().stream().anyMatch(value -> value.startsWith(actionKeyPrefix));
    }

    private List<GoalResearchService.ResearchResult> researchGoals(
            UUID runId,
            UUID profileId,
            UUID rerankProfileId,
            com.yanyue.rag.domain.agent.v4.RequestAnalysis analysis,
            RetrievalScope scope,
            ResearchPhase phase,
            EvidenceJudgeReasoner.JudgeDecision decision,
            int keywordTopK,
            int semanticTopK,
            int rerankCandidateLimit,
            double minimumRerankScore,
            AgentBudgetLedger ledger,
            GoalEvidencePool pool,
            java.util.Set<UUID> includedGoalIds
    ) {
        var futures = new ArrayList<CompletableFuture<GoalResearchService.ResearchResult>>();
        for (var goal : analysis.goals()) {
            if (!includedGoalIds.contains(goal.id())) continue;
            var goalDecision = decision == null ? null : decision.goals().stream()
                    .filter(value -> value.goalId().equals(goal.id())).findFirst().orElse(null);
            if (phase == ResearchPhase.REPAIR && (goalDecision == null
                    || goalDecision.goalStatus() == GoalStatus.SATISFIED_LOCKED
                    || goalDecision.repairQueries().isEmpty())) continue;
            var queries = phase == ResearchPhase.PRIMARY
                    ? List.of(goal.initialQuery()) : goalDecision.repairQueries();
            var targets = phase == ResearchPhase.PRIMARY ? List.<com.yanyue.rag.domain.agent.v4.RepairTarget>of()
                    : goalDecision.repairTargets();
            events.publish(runId, StreamEventType.GOAL_RESEARCH_STARTED,
                    Map.of("goalId", goal.id(), "phase", phase, "queryCount", queries.size()));
            futures.add(CompletableFuture.supplyAsync(() -> research.research(
                    profileId, rerankProfileId, runId, analysis.standaloneObjective(), goal, phase,
                    queries, targets, scope, keywordTopK, semanticTopK, rerankCandidateLimit,
                    minimumRerankScore, ledger, pool), executor).thenApply(result -> {
                        checkCancelled(runId);
                        persistResearch(runId, List.of(result));
                        return result;
                    }));
        }
        var remainingNanos = java.time.Duration.between(clock.instant(), ledger.deadline())
                .minusSeconds(2).toNanos();
        if (remainingNanos <= 0) throw new IllegalStateException("Run Deadline 已耗尽");
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(
                    remainingNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
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

    private void persistResearch(UUID runId, List<GoalResearchService.ResearchResult> results) {
        for (var result : results) {
            for (var evidence : result.acceptedEvidence()) artifacts.saveEvidence(runId, evidence);
            artifacts.saveGoalOutcome(runId, result.goalId(), result.phase(), result.health(),
                    result.searchTaskIds(), result.deepReadLogicalCallId(),
                    result.acceptedEvidence().stream().map(AcceptedEvidence::evidenceId).toList(),
                    result.mayHaveHiddenEvidence());
            var type = result.health().mayHideEvidence()
                    ? StreamEventType.GOAL_RESEARCH_FAILED : StreamEventType.GOAL_RESEARCH_COMPLETED;
            events.publish(runId, type, Map.of("goalId", result.goalId(), "phase", result.phase(),
                    "health", result.health(), "acceptedEvidenceCount", result.acceptedEvidence().size()));
        }
    }

    private String generateAnswer(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            String question,
            com.yanyue.rag.domain.agent.v4.RequestAnalysis analysis,
            CoverageStateReducer.CoverageState coverage,
            UUID profileId,
            int timeoutSeconds,
            List<AcceptedEvidence> allEvidence,
            AgentBudgetLedger ledger
    ) {
        var validEvidence = new ArrayList<AcceptedEvidence>();
        for (var evidence : allEvidence) {
            boolean valid = evidenceValidation.isCurrentlyValid(organizationId, userId, evidence, clock.instant());
            events.publish(runId, StreamEventType.CITATION_VERIFIED,
                    Map.of("evidenceId", evidence.evidenceId(), "valid", valid, "phase", "answer-pack"));
            if (valid) validEvidence.add(evidence);
        }
        if (validEvidence.isEmpty()) {
            throw new IllegalStateException("全部 Accepted Evidence 在最终回答前失效");
        }
        var selected = new ArrayList<>(fairEvidence(validEvidence, 8));
        var answerQuestion = truncateToTokens(question, 1_200);
        var answerContext = answerContext(analysis, coverage, selected);
        while (estimatedAnswerTokens(answerQuestion, answerContext, selected) > 7_000 && selected.size() > 1) {
            selected.removeLast();
            answerContext = answerContext(analysis, coverage, selected);
        }
        int estimatedInputTokens = estimatedAnswerTokens(answerQuestion, answerContext, selected);
        if (estimatedInputTokens > 7_000) {
            throw new IllegalStateException("最终回答输入超过 Token 上限");
        }
        var answerEvidence = new ArrayList<StreamingAnswerModelPort.AnswerEvidence>();
        var citationHits = new LinkedHashMap<String, RetrievalHit>();
        for (int index = 0; index < selected.size(); index++) {
            var evidence = selected.get(index);
            var id = "E" + (index + 1);
            var hit = citationHit(evidence);
            boolean valid = evidenceValidation.isCurrentlyValid(organizationId, userId, evidence, clock.instant());
            events.publish(runId, StreamEventType.CITATION_VERIFIED,
                    Map.of("evidenceId", id, "valid", valid, "phase", "pre-generation"));
            if (!valid) throw new IllegalStateException("最终回答候选 Evidence 已失效: " + id);
            answerEvidence.add(new StreamingAnswerModelPort.AnswerEvidence(id, evidence.titlePath(),
                    evidence.documentVersionId(), evidence.parentChunkId(), evidence.quote()));
            citationHits.put(id, hit);
        }
        var reservation = ledger.reserve("final-answer", Map.of(
                BudgetDimension.FINAL_ANSWER_CALL, 1L,
                BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL, 1L,
                BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT, 1L,
                BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN, (long) Math.max(1, estimatedInputTokens),
                BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN, 2_000L,
                BudgetDimension.FINAL_REFERENCE, (long) selected.size()), clock.instant());
        var logicalCallId = UUID.nameUUIDFromBytes((runId + ":final-answer")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        artifacts.reserveModelAttempt(runId, logicalCallId, null, "FINAL_ANSWER",
                "agentic-v4-final-answer", "agentic-v4-final-answer-v1", 1, reservation,
                answerQuestion.length() + answerContext.length()
                        + selected.stream().mapToInt(value -> value.quote().length()).sum());
        ledger.markDispatched(reservation.reservationId(), clock.instant());
        if (!artifacts.claimModelAttempt(reservation.reservationId())) {
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            throw new IllegalStateException("最终回答模型调用无法 claim");
        }
        events.publish(runId, StreamEventType.ANSWER_MODE_SELECTED,
                Map.of("mode", "ANSWER_WITH_EVIDENCE", "evidenceCount", selected.size()));
        StreamingAnswerModelPort.GenerationResult generation;
        long modelStarted = System.nanoTime();
        boolean modelAttemptCompleted = false;
        try {
            generation = answerModel.generate(profileId, new StreamingAnswerModelPort.AnswerRequest(
                    answerQuestion, answerContext, answerEvidence,
                    List.of(), remainingSeconds(ledger, Math.max(5, Math.min(120, timeoutSeconds))), 2_000),
                    delta -> events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", delta)), 1);
            var actual = new LinkedHashMap<BudgetDimension, Long>();
            if (generation.inputTokens() != null) actual.put(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN,
                    generation.inputTokens().longValue());
            if (generation.outputTokens() != null) actual.put(BudgetDimension.GENERATIVE_LLM_OUTPUT_TOKEN,
                    generation.outputTokens().longValue());
            long inputTokens = generation.inputTokens() == null
                    ? reservation.usage().get(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN)
                    : generation.inputTokens();
            long outputTokens = generation.outputTokens() == null ? AgenticV4ModelInvoker.estimatedTokens(
                    generation.content()) : generation.outputTokens();
            boolean tokenUsageEstimated = generation.inputTokens() == null || generation.outputTokens() == null;
            artifacts.completeModelAttempt(logicalCallId, reservation.reservationId(), 1, true, false,
                    tokenUsageEstimated,
                    inputTokens, outputTokens, elapsedMillis(modelStarted), null, sha256(generation.content()));
            artifacts.completeLogicalModelCall(logicalCallId, true, false, null, sha256(generation.content()));
            modelAttemptCompleted = true;
            ledger.succeed(reservation.reservationId(), actual, clock.instant());
        } catch (RuntimeException failure) {
            if (!modelAttemptCompleted) {
                artifacts.completeModelAttempt(logicalCallId, reservation.reservationId(), 1, false, false, true,
                        reservation.usage().get(BudgetDimension.GENERATIVE_LLM_INPUT_TOKEN), 0,
                        elapsedMillis(modelStarted), failure.getClass().getSimpleName(), null);
                artifacts.completeLogicalModelCall(logicalCallId, false, false,
                        failure.getClass().getSimpleName(), null);
                ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            }
            throw failure;
        }
        var referenced = referencedEvidence(generation.content());
        if (referenced.isEmpty()) throw new IllegalStateException("最终回答没有引用 Accepted Evidence");
        for (var id : referenced) {
            var hit = citationHits.get(id);
            int evidenceIndex = Integer.parseInt(id.substring(1)) - 1;
            var evidence = evidenceIndex >= 0 && evidenceIndex < selected.size()
                    ? selected.get(evidenceIndex) : null;
            boolean valid = hit != null && evidence != null
                    && evidenceValidation.isCurrentlyValid(organizationId, userId, evidence, clock.instant())
                    && citationValidation.isCurrentlyValid(organizationId, userId, hit, clock.instant());
            events.publish(runId, StreamEventType.CITATION_VERIFIED, Map.of("evidenceId", id, "valid", valid));
            if (!valid) throw new IllegalStateException("最终回答包含失效引用: " + id);
            citations.save(runId, Integer.parseInt(id.substring(1)), hit);
        }
        memory.append(conversationId, "user", question, runId);
        memory.append(conversationId, "assistant", generation.content(), runId);
        return generation.content();
    }

    private int estimatedAnswerTokens(
            String question,
            String context,
            List<AcceptedEvidence> evidence
    ) {
        return AgenticV4ModelInvoker.estimatedTokens(question)
                + AgenticV4ModelInvoker.estimatedTokens(context)
                + evidence.stream().mapToInt(value -> AgenticV4ModelInvoker.estimatedTokens(value.quote())
                        + AgenticV4ModelInvoker.estimatedTokens(value.titlePath()) + 40).sum();
    }

    private String truncateToTokens(String value, int maximumTokens) {
        if (value == null || value.isBlank()) return "";
        if (AgenticV4ModelInvoker.estimatedTokens(value) <= maximumTokens) return value;
        int low = 1;
        int high = value.length();
        int best = 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            if (AgenticV4ModelInvoker.estimatedTokens(value.substring(0, middle)) <= maximumTokens) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return value.substring(0, best);
    }

    private List<AcceptedEvidence> fairEvidence(List<AcceptedEvidence> evidence, int maximum) {
        var selected = new ArrayList<AcceptedEvidence>();
        var ordered = evidence.stream().sorted(
                java.util.Comparator.comparingDouble(AcceptedEvidence::retrievalScore).reversed()).toList();
        var representedRequirements = new java.util.LinkedHashSet<String>();
        for (var candidate : ordered) {
            boolean addsRequirement = candidate.activeRequirementIds().stream()
                    .map(requirementId -> candidate.goalId() + ":" + requirementId)
                    .anyMatch(key -> !representedRequirements.contains(key));
            if (!addsRequirement || selected.size() >= maximum) continue;
            selected.add(candidate);
            candidate.activeRequirementIds().forEach(
                    requirementId -> representedRequirements.add(candidate.goalId() + ":" + requirementId));
        }
        var representedDocuments = selected.stream().map(AcceptedEvidence::documentId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        ordered.stream().filter(value -> !selected.contains(value))
                .sorted(java.util.Comparator.comparing((AcceptedEvidence value) ->
                        representedDocuments.contains(value.documentId())))
                .limit(maximum - selected.size()).forEach(value -> {
                    selected.add(value);
                    representedDocuments.add(value.documentId());
                });
        return List.copyOf(selected);
    }

    private String answerContext(
            com.yanyue.rag.domain.agent.v4.RequestAnalysis analysis,
            CoverageStateReducer.CoverageState coverage,
            List<AcceptedEvidence> evidence
    ) {
        var answerableGoalIds = evidence.stream().map(AcceptedEvidence::goalId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var answerableGoals = analysis.goals().stream()
                .filter(goal -> answerableGoalIds.contains(goal.id())).toList();
        var context = new StringBuilder("仅回答以下存在直接证据的子问题：");
        answerableGoals.forEach(goal -> context.append("\n- ").append(goal.question()));
        var applicableConstraints = analysis.answerConstraints().stream()
                .filter(constraint -> answerableGoalIds.containsAll(constraint.appliesToGoalIds())).toList();
        if (!applicableConstraints.isEmpty()) {
            context.append("\n回答约束：");
            applicableConstraints.forEach(value -> context.append("\n- ").append(value.description()));
        }
        var conflicting = answerableGoals.stream().flatMap(goal -> goal.requirements().stream())
                .filter(requirement -> coverage.requirementStatuses().get(requirement.id())
                        == com.yanyue.rag.domain.agent.v4.RequirementStatus.CONFLICTING)
                .map(value -> value.description()).toList();
        if (!conflicting.isEmpty()) {
            context.append("\n以下证据面存在冲突，必须并列说明，不得自行消解：");
            conflicting.forEach(value -> context.append("\n- ").append(value));
        }
        return context.toString();
    }

    private RetrievalHit citationHit(AcceptedEvidence evidence) {
        var segment = evidence.sourceAnchor().segments().getFirst();
        return new RetrievalHit(evidence.parentChunkId(), null, evidence.documentId(), evidence.documentVersionId(),
                evidence.titlePath(), evidence.quote(), evidence.retrievalScore(),
                evidence.retrievalSources().stream().map(value -> value.name().toLowerCase()).toList(),
                segment.pageNumber(), segment.documentSourceStart(), segment.documentSourceEnd());
    }

    private List<String> referencedEvidence(String answer) {
        var result = new java.util.LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern.compile("\\[E(\\d+)]").matcher(answer == null ? "" : answer);
        while (matcher.find()) result.add("E" + matcher.group(1));
        return List.copyOf(result);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private int remainingSeconds(AgentBudgetLedger ledger, int configuredMaximum) {
        long remainingMillis = java.time.Duration.between(clock.instant(), ledger.deadline())
                .minusSeconds(2).toMillis();
        if (remainingMillis <= 0) throw new IllegalStateException("Run Deadline 已耗尽");
        return Math.max(1, (int) Math.min(configuredMaximum, (remainingMillis + 999) / 1_000));
    }

    private void reserveStage(AgentBudgetLedger ledger, String key) {
        var reservation = ledger.reserve(key, Map.of(BudgetDimension.SERIAL_SEMANTIC_STAGE, 1L), clock.instant());
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
            throw new CancellationException("Agentic RAG v4 已取消");
        }
    }

    private record CompletionInput(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            String question,
            UUID profileId,
            int timeoutSeconds,
            boolean generateAnswer,
            com.yanyue.rag.domain.agent.v4.RequestAnalysis analysis,
            GoalEvidencePool pool,
            List<GoalResearchService.ResearchResult> researchResults,
            CoverageStateReducer.CoverageState coverage,
            AgentBudgetLedger ledger
    ) {
        private CompletionInput {
            researchResults = List.copyOf(researchResults);
        }
    }
}
