package com.yanyue.rag.application.chat;

import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.application.chat.v4.AgenticRagV4Pipeline;
import com.yanyue.rag.application.chat.v5.AgenticRagV5Pipeline;
import com.yanyue.rag.application.chat.v7.AgenticRagV7Pipeline;
import com.yanyue.rag.application.chat.v8.AgenticRagV8Pipeline;
import com.yanyue.rag.domain.port.AgenticV4RecoveryPort;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.domain.agent.AgentRunState;
import com.yanyue.rag.domain.agent.AgentStage;
import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.EvidenceItem;
import com.yanyue.rag.domain.agent.FactItem;
import com.yanyue.rag.domain.agent.FactStatus;
import com.yanyue.rag.domain.agent.FactSupport;
import com.yanyue.rag.domain.agent.QuestionPlan;
import com.yanyue.rag.domain.agent.RetrievalTask;
import com.yanyue.rag.domain.agent.SearchMode;
import com.yanyue.rag.domain.agent.SubQuestion;
import com.yanyue.rag.domain.agent.SubQuestionCoverage;
import com.yanyue.rag.domain.agent.SupportedSurface;
import com.yanyue.rag.domain.model.PipelineConfig;
import com.yanyue.rag.domain.port.AgentRunArtifactPort;
import com.yanyue.rag.domain.port.AgentRecoveryPort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.CitationValidationPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.MemoryFactRepository;
import com.yanyue.rag.domain.port.QueryRewriteModelPort;
import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.port.RetrievalTracePort;
import com.yanyue.rag.domain.port.RunRecordPort;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgenticRagPipeline {
    private AgenticRagV4Pipeline v4Pipeline;
    private AgenticRagV5Pipeline v5Pipeline;
    private AgenticRagV7Pipeline v7Pipeline;
    private AgenticRagV8Pipeline v8Pipeline;
    private final ReactAgentEngine reactEngine;
    private final RetrievalPort retrieval;
    private final RetrievalTracePort traces;
    private final RerankModelPort rerank;
    private final QueryRewriteModelPort queryRewrite;
    private final AgentStructuredReasoner reasoner;
    private final StreamingAnswerModelPort answerModel;
    private final PipelineConfigService pipelineConfigs;
    private final ConversationMemoryPort memory;
    private final MemoryFactRepository memoryFacts;
    private final AgentRunArtifactPort artifacts;
    private final CitationPort citations;
    private final CitationValidationPort citationValidation;
    private final RunRecordPort runRecords;
    private final RunEventHub events;
    private final Executor executor;
    private final Clock clock;
    private final MetadataSchemaService metadataSchemas;
    private final RagTelemetry telemetry;
    private final DeepReadEvidenceSelector deepReadSelector = new DeepReadEvidenceSelector();
    private final AgentCandidateGate candidateGate = new AgentCandidateGate();
    private final PartialAnswerPolicy partialAnswerPolicy = new PartialAnswerPolicy();

    public AgenticRagPipeline(
            ReactAgentEngine reactEngine,
            RetrievalPort retrieval,
            RetrievalTracePort traces,
            RerankModelPort rerank,
            QueryRewriteModelPort queryRewrite,
            AgentStructuredReasoner reasoner,
            StreamingAnswerModelPort answerModel,
            PipelineConfigService pipelineConfigs,
            ConversationMemoryPort memory,
            MemoryFactRepository memoryFacts,
            AgentRunArtifactPort artifacts,
            CitationPort citations,
            CitationValidationPort citationValidation,
            RunRecordPort runRecords,
            RunEventHub events,
            @Qualifier("ragRunExecutor") Executor executor,
            Clock clock,
            MetadataSchemaService metadataSchemas,
            RagTelemetry telemetry
    ) {
        this.reactEngine = reactEngine;
        this.retrieval = retrieval;
        this.traces = traces;
        this.rerank = rerank;
        this.queryRewrite = queryRewrite;
        this.reasoner = reasoner;
        this.answerModel = answerModel;
        this.pipelineConfigs = pipelineConfigs;
        this.memory = memory;
        this.memoryFacts = memoryFacts;
        this.artifacts = artifacts;
        this.citations = citations;
        this.citationValidation = citationValidation;
        this.runRecords = runRecords;
        this.events = events;
        this.executor = executor;
        this.clock = clock;
        this.metadataSchemas = metadataSchemas;
        this.telemetry = telemetry;
    }

    public AgenticRagPipeline(
            ReactAgentEngine reactEngine,
            RetrievalPort retrieval,
            RetrievalTracePort traces,
            RerankModelPort rerank,
            QueryRewriteModelPort queryRewrite,
            AgentStructuredReasoner reasoner,
            StreamingAnswerModelPort answerModel,
            PipelineConfigService pipelineConfigs,
            ConversationMemoryPort memory,
            MemoryFactRepository memoryFacts,
            AgentRunArtifactPort artifacts,
            CitationPort citations,
            CitationValidationPort citationValidation,
            RunRecordPort runRecords,
            RunEventHub events,
            @Qualifier("ragRunExecutor") Executor executor,
            Clock clock,
            MetadataSchemaService metadataSchemas,
            RagTelemetry telemetry,
            AgenticRagV4Pipeline v4Pipeline
    ) {
        this(reactEngine, retrieval, traces, rerank, queryRewrite, reasoner, answerModel, pipelineConfigs,
                memory, memoryFacts, artifacts, citations, citationValidation, runRecords, events, executor,
                clock, metadataSchemas, telemetry);
        this.v4Pipeline = v4Pipeline;
    }

    @Autowired
    public AgenticRagPipeline(
            ReactAgentEngine reactEngine,
            RetrievalPort retrieval,
            RetrievalTracePort traces,
            RerankModelPort rerank,
            QueryRewriteModelPort queryRewrite,
            AgentStructuredReasoner reasoner,
            StreamingAnswerModelPort answerModel,
            PipelineConfigService pipelineConfigs,
            ConversationMemoryPort memory,
            MemoryFactRepository memoryFacts,
            AgentRunArtifactPort artifacts,
            CitationPort citations,
            CitationValidationPort citationValidation,
            RunRecordPort runRecords,
            RunEventHub events,
            @Qualifier("ragRunExecutor") Executor executor,
            Clock clock,
            MetadataSchemaService metadataSchemas,
            RagTelemetry telemetry,
            AgenticRagV4Pipeline v4Pipeline,
            AgenticRagV5Pipeline v5Pipeline,
            @Qualifier("agenticRagV7Pipeline") AgenticRagV7Pipeline v7Pipeline,
            @Qualifier("agenticRagV8Pipeline") AgenticRagV8Pipeline v8Pipeline
    ) {
        this(reactEngine, retrieval, traces, rerank, queryRewrite, reasoner, answerModel, pipelineConfigs,
                memory, memoryFacts, artifacts, citations, citationValidation, runRecords, events, executor,
                clock, metadataSchemas, telemetry, v4Pipeline);
        this.v5Pipeline = v5Pipeline;
        this.v7Pipeline = v7Pipeline;
        this.v8Pipeline = v8Pipeline;
    }

    public String execute(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            CreateRunRequest request
    ) {
        if (v8Pipeline != null) {
            return v8Pipeline.execute(runId, conversationId, organizationId, userId, request, true);
        }
        if (v7Pipeline != null) {
            return v7Pipeline.execute(runId, conversationId, organizationId, userId, request, true);
        }
        if (v5Pipeline != null) {
            return v5Pipeline.execute(runId, conversationId, organizationId, userId, request, true);
        }
        if (v4Pipeline != null) {
            return v4Pipeline.execute(runId, conversationId, organizationId, userId, request, true);
        }
        return execute(runId, conversationId, organizationId, userId, request, true);
    }

    public String executeRetrievalOnly(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            CreateRunRequest request
    ) {
        if (v8Pipeline != null) {
            return v8Pipeline.execute(runId, conversationId, organizationId, userId, request, false);
        }
        if (v7Pipeline != null) {
            return v7Pipeline.execute(runId, conversationId, organizationId, userId, request, false);
        }
        if (v5Pipeline != null) {
            return v5Pipeline.execute(runId, conversationId, organizationId, userId, request, false);
        }
        if (v4Pipeline != null) {
            return v4Pipeline.execute(runId, conversationId, organizationId, userId, request, false);
        }
        return execute(runId, conversationId, organizationId, userId, request, false);
    }

    public String resumeV4(
            AgenticV4RecoveryPort.RecoverableRun run,
            AgenticV4RecoveryPort.RecoverySnapshot snapshot
    ) {
        if (v4Pipeline == null) throw new IllegalStateException("Agentic RAG v4 Pipeline 未启用");
        return v4Pipeline.resume(run, snapshot, true);
    }

    private String execute(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            CreateRunRequest request,
            boolean generateAnswer
    ) {
        var validatedFilters = metadataSchemas.validateFilters(
                organizationId, request.scope().knowledgeBaseIds(), request.filters());
        var config = pipelineConfigs.resolve(organizationId, request.modelProfileId());
        var chatProfileId = request.modelProfileId() == null ? config.chatProfileId() : request.modelProfileId();
        runRecords.applyAgentHybridRuntime(runId, config, chatProfileId);

        var normalizedQuery = request.query().strip().replaceAll("\\s+", " ");
        var recent = memory.recentMessages(conversationId, config.recentTurns());
        QueryRewriteModelPort.RewriteResult rewrite;
        try {
            rewrite = shouldRewrite(normalizedQuery, recent)
                    ? queryRewrite.rewrite(config.queryRewriteProfileId(), normalizedQuery, recent)
                    : QueryRewriteModelPort.RewriteResult.unchanged(normalizedQuery, "not-required");
        } catch (RuntimeException failure) {
            rewrite = QueryRewriteModelPort.RewriteResult.unchanged(normalizedQuery, safeMessage(failure));
        }
        var standaloneQuery = rewrite.standaloneQuery() == null || rewrite.standaloneQuery().isBlank()
                ? normalizedQuery : rewrite.standaloneQuery().strip();
        if (rewrite.rewriteNeeded() && !standaloneQuery.equals(normalizedQuery)) {
            events.publish(runId, StreamEventType.QUERY_REWRITTEN, Map.of(
                    "original", normalizedQuery, "rewritten", standaloneQuery,
                    "resolvedReferences", rewrite.resolvedReferences(), "profileId", config.queryRewriteProfileId()));
        } else if (rewrite.fallbackReason() != null && !"not-required".equals(rewrite.fallbackReason())) {
            events.publish(runId, StreamEventType.QUERY_REWRITTEN, Map.of(
                    "original", normalizedQuery, "rewritten", normalizedQuery,
                    "fallback", true, "reason", rewrite.fallbackReason(),
                    "profileId", config.queryRewriteProfileId()));
        }

        var now = clock.instant();
        var budget = new com.yanyue.rag.domain.agent.AgentBudget(
                config.maxRetrievalRounds(), Math.min(6, config.maxSubQueries()), config.maxSearchCalls(),
                config.maxDeepReadCalls(), 4, Duration.ofSeconds(config.agenticLoopTimeoutSeconds()),
                0, 0, 0, now);
        var state = new AgentRunState(runId, AgentStage.ROUTE, budget, now, now);
        artifacts.checkpoint(state, null);
        checkCancelled(runId, state);

        move(state, AgentStage.PLAN, null);
        var plan = reasoner.plan(chatProfileId, runId, standaloneQuery, state.budget().maxSubQuestions());
        artifacts.checkpoint(state, plan);
        events.publish(runId, StreamEventType.PLAN_CREATED, plan);

        var scope = RetrievalScope.forUser(organizationId, userId, request.scope().knowledgeBaseIds(),
                request.scope().documentIds(), validatedFilters, clock.instant());
        var evidence = new ArrayList<EvidenceItem>();
        var evidenceHits = new LinkedHashMap<UUID, RetrievalHit>();
        var physicallyReadChunks = new HashSet<UUID>();
        var evidenceAssignments = new HashSet<DeepReadEvidenceSelector.AssignmentKey>();
        var previousQueries = new ArrayList<String>();
        var nextQueries = initialQueries(plan);
        CoverageReport coverage = null;
        int stagnantRounds = 0;
        var judgeFailureRounds = new HashSet<Integer>();

        while (!nextQueries.isEmpty()
                && state.budget().roundsUsed() < state.budget().maxRounds()
                && state.budget().searchesUsed() < state.budget().maxSearches()
                && !state.budget().timedOut(clock.instant())) {
            checkCancelled(runId, state);
            state.useRound();
            publishBudget(runId, state, "round-started");
            move(state, AgentStage.RETRIEVE, plan);

            var tasks = createTasks(runId, state, plan, scope, nextQueries,
                    Math.max(config.keywordTopK(), config.semanticTopK()));
            if (tasks.isEmpty()) break;
            artifacts.annotateCheckpoint(runId, Map.of(
                    "currentRound", state.budget().roundsUsed(),
                    "pendingTaskIds", tasks.stream().map(RetrievalTask::id).toList(),
                    "completedTaskIds", List.of(),
                    "taskErrors", Map.of()
            ));
            previousQueries.addAll(tasks.stream().map(RetrievalTask::query).toList());
            var outcomes = executeTasks(runId, state, config, tasks);
            var failedTasks = tasks.stream().filter(task -> outcomes.stream()
                    .anyMatch(outcome -> outcome.task().id().equals(task.id()) && outcome.failed())).toList();
            artifacts.annotateCheckpoint(runId, Map.of(
                    "pendingTaskIds", List.of(),
                    "completedTaskIds", outcomes.stream().filter(outcome -> !outcome.failed())
                            .map(outcome -> outcome.task().id()).toList(),
                    "taskErrors", failedTasks.stream().collect(java.util.stream.Collectors.toMap(
                            task -> task.id().toString(), task -> "retrieval-task-failed"))
            ));

            move(state, AgentStage.DEEP_READ, plan);
            int evidenceBefore = evidence.size();
            var newEvidence = deepRead(
                    runId, state, chatProfileId, plan, outcomes, evidence, evidenceHits,
                    physicallyReadChunks, evidenceAssignments);
            move(state, AgentStage.COVERAGE_JUDGE, plan);
            coverage = judgeEvidence(runId, state, chatProfileId, plan, evidence, judgeFailureRounds);
            for (var item : coverage.items()) {
                if (!item.covered() || item.hasConflict()) {
                    events.publish(runId, StreamEventType.GAP_IDENTIFIED, Map.of(
                            "subQuestionId", item.subQuestionId(), "gaps", item.gaps(),
                            "hasConflict", item.hasConflict(), "evidenceFamilies", item.deepReadEvidenceFamilies()));
                }
            }

            stagnantRounds = evidence.size() == evidenceBefore || newEvidence.isEmpty() ? stagnantRounds + 1 : 0;
            if (judgeFailureRounds.contains(state.budget().roundsUsed())
                    || coverage.sufficient() || stagnantRounds >= 2 || state.budget().exhausted()
                    || state.budget().timedOut(clock.instant())) {
                nextQueries = List.of();
                break;
            }

            move(state, AgentStage.GAP_SEARCH, plan);
            try {
                nextQueries = reasoner.gapQueries(chatProfileId, plan, coverage, previousQueries);
            } catch (RuntimeException failure) {
                // 缺口规划失败不应把可审计的“证据不足”变成基础设施失败；
                // 保守结果是不再检索，并按 Judge 原始缺口持久化无答案结果。
                nextQueries = List.of();
                events.publish(runId, StreamEventType.GAP_IDENTIFIED, Map.of(
                        "phase", "gap-query-generation",
                        "reason", safeMessage(failure),
                        "round", state.budget().roundsUsed()));
            }
            artifacts.annotateCheckpoint(runId, Map.of("gaps", nextQueries));
            for (var gap : nextQueries) {
                events.publish(runId, StreamEventType.GAP_QUERY_CREATED, Map.of(
                        "subQuestionId", gap.subQuestionId(), "query", gap.query(),
                        "searchMode", gap.searchMode(), "round", state.budget().roundsUsed() + 1));
            }
        }

        if (coverage == null) {
            // 即使预算或超时导致首轮检索无法开始，结束前也必须执行 Judge。
            if (state.stage() == AgentStage.PLAN) move(state, AgentStage.RETRIEVE, plan);
            if (state.stage() == AgentStage.RETRIEVE) move(state, AgentStage.DEEP_READ, plan);
            if (state.stage() == AgentStage.DEEP_READ) move(state, AgentStage.COVERAGE_JUDGE, plan);
            coverage = judgeEvidence(runId, state, chatProfileId, plan, evidence, judgeFailureRounds);
        }
        if (state.stage() == AgentStage.GAP_SEARCH || state.stage() == AgentStage.COVERAGE_JUDGE) {
            move(state, AgentStage.SYNTHESIZE, plan);
        } else if (state.stage() != AgentStage.SYNTHESIZE) {
            throw new IllegalStateException("Agent stopped in an unexpected stage: " + state.stage());
        }
        checkCancelled(runId, state);
        if (!generateAnswer) {
            move(state, AgentStage.VERIFY, plan);
            move(state, AgentStage.COMPLETED, plan);
            artifacts.annotateCheckpoint(runId, Map.of(
                    "answerGenerationSkipped", true,
                    "retrievalEvidenceCount", evidence.size(),
                    "evidenceJudgeSufficient", coverage != null && coverage.sufficient()
            ));
            return "";
        }
        var partialDecision = partialAnswerPolicy.decide(plan, coverage, evidence, judgeFailureRounds);
        String answer;
        if (coverage != null && coverage.sufficient()) {
            answer = synthesizeEvidence(
                    runId, conversationId, organizationId, userId, normalizedQuery, standaloneQuery,
                    chatProfileId, config.llmTimeoutSeconds(), evidence, evidenceHits,
                    config.maxFinalReferences());
        } else if (partialDecision.isPresent()) {
            answer = renderPartialAnswer(
                    runId, conversationId, organizationId, userId, normalizedQuery,
                    partialDecision.orElseThrow(), evidence, evidenceHits, config.maxFinalReferences(), coverage);
        } else {
            answer = noAnswerForCoverage(runId, conversationId, normalizedQuery, coverage);
        }
        move(state, AgentStage.VERIFY, plan);
        move(state, AgentStage.COMPLETED, plan);
        return answer;
    }

    public String executeReact(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            CreateRunRequest request
    ) {
        return reactEngine.execute(runId, conversationId, organizationId, userId, request, true);
    }

    public String resumeFromCheckpoint(
            AgentRecoveryPort.RecoverableRun run,
            AgentRecoveryPort.RecoverySnapshot snapshot
    ) {
        var config = pipelineConfigs.resolve(run.organizationId(), run.request().modelProfileId());
        var chatProfileId = run.request().modelProfileId() == null
                ? config.chatProfileId() : run.request().modelProfileId();
        runRecords.applyRuntime(run.runId(), config, chatProfileId);
        var state = new AgentRunState(
                run.runId(), AgentStage.SYNTHESIZE, snapshot.state().budget(),
                snapshot.state().createdAt(), clock.instant());
        artifacts.checkpoint(state, snapshot.plan());
        checkCancelled(run.runId(), state);
        var answer = synthesize(
                run.runId(), run.conversationId(), run.organizationId(), run.userId(),
                run.request().query().strip(), chatProfileId, config.fastTimeoutSeconds(),
                snapshot.coverage(), snapshot.facts(),
                snapshot.evidenceHits());
        move(state, AgentStage.VERIFY, snapshot.plan());
        move(state, AgentStage.COMPLETED, snapshot.plan());
        return answer;
    }

    private List<AgentStructuredReasoner.GapQuery> initialQueries(QuestionPlan plan) {
        return plan.subQuestions().stream().map(question -> new AgentStructuredReasoner.GapQuery(
                question.id(), question.question(), question.searchMode())).toList();
    }

    private List<RetrievalTask> createTasks(
            UUID runId,
            AgentRunState state,
            QuestionPlan plan,
            RetrievalScope scope,
            List<AgentStructuredReasoner.GapQuery> queries,
            int topK
    ) {
        var knownQuestions = plan.subQuestions().stream().map(SubQuestion::id).collect(java.util.stream.Collectors.toSet());
        var tasks = new ArrayList<RetrievalTask>();
        for (var query : queries) {
            if (!knownQuestions.contains(query.subQuestionId())) continue;
            int physicalSearches = query.searchMode() == SearchMode.HYBRID ? 2 : 1;
            if (state.budget().searchesUsed() > state.budget().maxSearches() - physicalSearches) break;
            state.useSearches(physicalSearches);
            var task = new RetrievalTask(UUID.randomUUID(), query.subQuestionId(), query.query(),
                    query.searchMode(), scope, topK);
            tasks.add(task);
            artifacts.saveTask(runId, state.budget().roundsUsed(), task, "PENDING", 0, null);
            events.publish(runId, StreamEventType.RETRIEVAL_TASK_CREATED, Map.of(
                    "taskId", task.id(), "subQuestionId", task.subQuestionId(), "query", task.query(),
                    "searchMode", task.searchMode(), "round", state.budget().roundsUsed()));
            publishBudget(runId, state, "search-reserved");
        }
        return List.copyOf(tasks);
    }

    private List<TaskOutcome> executeTasks(
            UUID runId,
            AgentRunState state,
            PipelineConfig config,
            List<RetrievalTask> tasks
    ) {
        var outcomes = new ArrayList<TaskOutcome>();
        int parallelism = state.budget().maxParallelism();
        for (int offset = 0; offset < tasks.size(); offset += parallelism) {
            checkCancelled(runId, state);
            var batch = tasks.subList(offset, Math.min(tasks.size(), offset + parallelism));
            var futures = batch.stream().map(task -> CompletableFuture.supplyAsync(
                    () -> executeTask(runId, state.budget().roundsUsed(), task, config),
                    executor)).toList();
            try {
                for (var future : futures) outcomes.add(future.get());
            } catch (InterruptedException exception) {
                futures.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("Agent retrieval was interrupted");
            } catch (java.util.concurrent.ExecutionException exception) {
                futures.forEach(future -> future.cancel(true));
                var cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) throw runtimeException;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Agent retrieval task failed", cause);
            }
            checkCancelled(runId, state);
        }
        return List.copyOf(outcomes);
    }

    private TaskOutcome executeTask(
            UUID runId,
            int round,
            RetrievalTask task,
            PipelineConfig config
    ) {
        artifacts.saveTask(runId, round, task, "RUNNING", 0, null);
        events.publish(runId, StreamEventType.RETRIEVAL_TASK_STARTED, Map.of(
                "taskId", task.id(), "query", task.query(), "searchMode", task.searchMode()));
        try {
            var started = System.nanoTime();
            var keywordFuture = task.searchMode() == SearchMode.SEMANTIC
                    ? CompletableFuture.completedFuture(List.<RetrievalHit>of())
                    : CompletableFuture.supplyAsync(() -> retrieval.keywordSearch(
                            task.query(), task.scope(), config.keywordTopK()), executor);
            var semanticFuture = task.searchMode() == SearchMode.KEYWORD
                    ? CompletableFuture.completedFuture(List.<RetrievalHit>of())
                    : CompletableFuture.supplyAsync(() -> retrieval.semanticSearch(
                            task.query(), task.scope(), config.semanticTopK(), 4), executor);
            var keyword = keywordFuture.join();
            var semantic = semanticFuture.join();
            var candidates = task.searchMode() == SearchMode.HYBRID
                    ? ReciprocalRankFusion.fuse(List.of(keyword, semantic), config.rrfCandidateLimit())
                    : task.searchMode() == SearchMode.KEYWORD ? keyword : semantic;
            var reranked = rerankTask(runId, round, task, config.rerankProfileId(), candidates,
                    config.minimumRerankScore(), config.rerankCandidateLimit());
            saveTaskTrace(runId, task, started, keyword, semantic, candidates, reranked);
            artifacts.saveTask(runId, round, task, "SUCCEEDED", reranked.size(), null);
            events.publish(runId, StreamEventType.RETRIEVAL_TASK_COMPLETED, Map.of(
                    "taskId", task.id(), "resultCount", reranked.size(),
                    "top", summaries(reranked, 3), "round", round));
            return new TaskOutcome(task, reranked, false);
        } catch (RuntimeException failure) {
            var message = safeMessage(failure);
            artifacts.saveTask(runId, round, task, "FAILED", 0, message);
            events.publish(runId, StreamEventType.RETRIEVAL_TASK_FAILED, Map.of(
                    "taskId", task.id(), "message", message, "round", round));
            return new TaskOutcome(task, List.of(), true);
        }
    }

    private void saveTaskTrace(
            UUID runId,
            RetrievalTask task,
            long startedNanos,
            List<RetrievalHit> keyword,
            List<RetrievalHit> semantic,
            List<RetrievalHit> candidates,
            List<RetrievalHit> reranked
    ) {
        var keywordRanks = ranks(keyword);
        var semanticRanks = ranks(semantic);
        var retrievalScores = candidates.stream().collect(java.util.stream.Collectors.toMap(
                RetrievalHit::chunkId, RetrievalHit::score, Math::max));
        var rerankScores = reranked.stream()
                .filter(hit -> hit.sources().contains("rerank"))
                .collect(java.util.stream.Collectors.toMap(
                        RetrievalHit::chunkId, RetrievalHit::score, Math::max));
        var all = new LinkedHashMap<UUID, RetrievalHit>();
        candidates.forEach(hit -> all.putIfAbsent(hit.chunkId(), hit));
        reranked.forEach(hit -> all.put(hit.chunkId(), hit));
        traces.save(runId, task.subQuestionId(), task.query(), task.searchMode().name(),
                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000),
                all.values().stream().map(hit -> new RetrievalTracePort.CandidateTrace(
                        hit, keywordRanks.get(hit.chunkId()), semanticRanks.get(hit.chunkId()),
                        retrievalScores.get(hit.chunkId()), rerankScores.get(hit.chunkId()), false
                )).toList());
    }

    private Map<UUID, Integer> ranks(List<RetrievalHit> hits) {
        var ranks = new LinkedHashMap<UUID, Integer>();
        for (int index = 0; index < hits.size(); index++) {
            ranks.putIfAbsent(hits.get(index).chunkId(), index + 1);
        }
        return Map.copyOf(ranks);
    }

    private List<RetrievalHit> rerankTask(
            UUID runId,
            int round,
            RetrievalTask task,
            UUID profileId,
            List<RetrievalHit> candidates,
            double minimumScore,
            int topK
    ) {
        if (candidates.isEmpty()) return List.of();
        try {
            var scores = rerank.rerank(profileId, task.query(), candidates.stream().map(RetrievalHit::text).toList(),
                    Math.min(topK, candidates.size()));
            if (scores == null || scores.isEmpty()) {
                throw new IllegalStateException("Rerank returned no candidate scores");
            }
            var selected = candidateGate.select(candidates, scores, minimumScore).stream().limit(topK).toList();
            events.publish(runId, StreamEventType.RERANK_COMPLETED, Map.of(
                    "taskId", task.id(), "query", task.query(), "candidateCount", candidates.size(),
                    "resultCount", selected.size(), "round", round));
            return selected;
        } catch (RuntimeException failure) {
            events.publish(runId, StreamEventType.RERANK_SKIPPED, Map.of(
                    "taskId", task.id(), "query", task.query(), "candidateCount", candidates.size(),
                    "reason", safeMessage(failure), "fallback", "retrieval-order", "round", round));
            return candidates.stream().limit(topK).toList();
        }
    }

    private List<EvidenceItem> deepRead(
            UUID runId,
            AgentRunState state,
            UUID profileId,
            QuestionPlan plan,
            List<TaskOutcome> outcomes,
            List<EvidenceItem> allEvidence,
            Map<UUID, RetrievalHit> evidenceHits,
            Set<UUID> physicallyReadChunks,
            Set<DeepReadEvidenceSelector.AssignmentKey> evidenceAssignments
    ) {
        var created = new ArrayList<EvidenceItem>();
        var groups = new ArrayList<DeepReadEvidenceSelector.CandidateGroup>();
        for (var outcome : outcomes) {
            if (outcome.failed() || outcome.hits().isEmpty()) continue;
            try {
                groups.add(new DeepReadEvidenceSelector.CandidateGroup(
                        outcome.task().id(), outcome.task().subQuestionId(),
                        retrieval.expandContext(outcome.hits(), 3)));
            } catch (RuntimeException failure) {
                events.publish(runId, StreamEventType.DEEP_READ_FAILED, Map.of(
                        "taskId", outcome.task().id(),
                        "subQuestionId", outcome.task().subQuestionId(),
                        "phase", "context-expansion",
                        "reason", safeMessage(failure),
                        "round", state.budget().roundsUsed()));
            }
        }
        int remainingPhysicalReads = Math.max(
                0, state.budget().maxDeepReads() - state.budget().deepReadsUsed());
        var selections = deepReadSelector.select(
                groups, evidenceAssignments, physicallyReadChunks, remainingPhysicalReads, 2, 2);
        var selectionsByQuestion = selections.stream().collect(java.util.stream.Collectors.groupingBy(
                DeepReadEvidenceSelector.Selection::subQuestionId, LinkedHashMap::new,
                java.util.stream.Collectors.toList()));
        var questions = plan.subQuestions().stream().collect(java.util.stream.Collectors.toMap(
                SubQuestion::id, SubQuestion::question));
        var contextsByKey = new LinkedHashMap<String, DeepReadEvidenceSelector.Selection>();
        var spansByKey = new LinkedHashMap<String, AgentStructuredReasoner.EvidenceSpan>();

        for (var selection : selections) {
            checkCancelled(runId, state);
            if (selection.physicalRead()) {
                state.useDeepRead();
                publishBudget(runId, state, "deep-read-consumed");
                physicallyReadChunks.add(selection.hit().chunkId());
            }
        }

        var extractionRequests = new ArrayList<EvidenceExtractionRequest>();
        for (var entry : selectionsByQuestion.entrySet()) {
            var contexts = new ArrayList<AgentStructuredReasoner.EvidenceContext>();
            int index = 0;
            for (var selection : entry.getValue()) {
                var key = entry.getKey() + ":c" + (++index);
                contextsByKey.put(key, selection);
                contexts.add(new AgentStructuredReasoner.EvidenceContext(key, selection.hit().text()));
            }
            extractionRequests.add(new EvidenceExtractionRequest(
                    entry.getKey(), contexts.size(), questions.getOrDefault(entry.getKey(), ""), contexts));
        }

        // 按子问题分批并串行抽取证据：单次请求可校验多个独立上下文组，
        // 同时保持载荷有界，并避免评测并发在同一 Provider Key 后堆积长请求。
        int extractionBatchSize = 2;
        for (int offset = 0; offset < extractionRequests.size(); offset += extractionBatchSize) {
            var batch = extractionRequests.subList(
                    offset, Math.min(extractionRequests.size(), offset + extractionBatchSize));
            var requests = batch.stream().map(request -> new AgentStructuredReasoner.EvidenceRequest(
                    request.subQuestionId().toString(), request.question(), request.contexts())).toList();
            try {
                var spans = reasoner.extractEvidenceSpansBatch(profileId, requests);
                for (var span : spans) spansByKey.put(span.contextKey(), span);
                // 成功完成的抽取即使结果为空也算真实尝试，下一轮可去重；
                // 传输或契约失败不在此标记，以便同一任务后续重新尝试。
                for (var request : batch) {
                    for (var context : request.contexts()) {
                        var selection = contextsByKey.get(context.key());
                        if (selection != null) {
                            evidenceAssignments.add(new DeepReadEvidenceSelector.AssignmentKey(
                                    selection.subQuestionId(), selection.hit().chunkId()));
                        }
                    }
                }
            } catch (RuntimeException failure) {
                for (var request : batch) {
                    events.publish(runId, StreamEventType.DEEP_READ_FAILED, Map.of(
                            "subQuestionId", request.subQuestionId(), "contextCount", request.contextCount(),
                            "phase", "evidence-extraction",
                            "reason", safeMessage(failure), "round", state.budget().roundsUsed()));
                }
            }
            checkCancelled(runId, state);
        }

        for (var entry : spansByKey.entrySet()) {
            var selection = contextsByKey.get(entry.getKey());
            if (selection == null) continue;
            var span = entry.getValue();
            var hit = selection.hit();
            var sources = selection.physicalRead()
                    ? append(hit.sources(), "evidence-span")
                    : append(append(hit.sources(), "cross-question-reuse"), "evidence-span");
            var base = hit.sourceStart() == null ? 0 : hit.sourceStart();
            var sourceStart = base + span.startOffset();
            var sourceEnd = base + span.endOffset();
            var evidenceHit = hit.withTextAndSource(span.quote(), sourceStart, sourceEnd)
                    .withScore(hit.score(), sources);
            var evidence = new EvidenceItem(
                    UUID.randomUUID(), selection.subQuestionId(), evidenceHit.documentId(),
                    evidenceHit.documentVersionId(), evidenceHit.chunkId(), evidenceHit.text(),
                    sourceStart, sourceEnd,
                    evidenceHit.score(), true, evidenceHit.sources()
            );
            created.add(evidence);
            allEvidence.add(evidence);
            evidenceHits.put(evidence.id(), evidenceHit);
            artifacts.saveEvidence(runId, evidence);
            events.publish(runId, StreamEventType.DEEP_READ_COMPLETED, evidence);
        }
        return List.copyOf(created);
    }

    private CoverageReport judgeEvidence(
            UUID runId,
            AgentRunState state,
            UUID profileId,
            QuestionPlan plan,
            List<EvidenceItem> evidence,
            Set<Integer> judgeFailureRounds
    ) {
        var round = Math.max(1, state.budget().roundsUsed());
        events.publish(runId, StreamEventType.EVIDENCE_JUDGE_STARTED, Map.of(
                "round", round, "evidenceCount", evidence.size(),
                "subQuestionCount", plan.subQuestions().size()));
        CoverageReport report;
        boolean judgeFailed = false;
        try {
            report = reasoner.evidenceCoverage(profileId, runId, plan, evidence);
        } catch (RuntimeException failure) {
            judgeFailureRounds.add(round);
            judgeFailed = true;
            events.publish(runId, StreamEventType.EVIDENCE_JUDGE_FAILED, Map.of(
                    "round", round, "evidenceCount", evidence.size(),
                    "subQuestionCount", plan.subQuestions().size(),
                    "reason", safeMessage(failure)));
            var fallbackItems = plan.subQuestions().stream().map(question -> {
                var families = evidence.stream()
                        .filter(item -> item.deepRead() && item.subQuestionId().equals(question.id()))
                        .map(EvidenceItem::documentVersionId)
                        .collect(java.util.stream.Collectors.toSet());
                return new com.yanyue.rag.domain.agent.SubQuestionCoverage(
                        question.id(), false, families.size(),
                        List.of("Evidence Judge 调用失败，无法确认该子问题的证据充分性"), false);
            }).toList();
            report = new CoverageReport(runId, fallbackItems);
        }
        artifacts.saveCoverage(runId, round, report);
        if (!judgeFailed) {
            events.publish(runId, StreamEventType.EVIDENCE_JUDGE_COMPLETED, Map.of(
                    "round", round, "sufficient", report.sufficient(),
                    "evidenceCount", evidence.size(), "items", report.items()));
        }
        events.publish(runId, StreamEventType.COVERAGE_UPDATED, report);
        return report;
    }

    private void extractFacts(
            UUID runId,
            UUID profileId,
            QuestionPlan plan,
            List<EvidenceItem> newEvidence,
            List<FactItem> facts
    ) {
        var futures = plan.subQuestions().stream().map(question -> {
            var relevant = newEvidence.stream()
                    .filter(item -> item.subQuestionId().equals(question.id())).toList();
            return CompletableFuture.supplyAsync(() -> new FactExtraction(
                    question, relevant,
                    reasoner.extractAndVerifyFacts(profileId, question, relevant)), executor);
        }).toList();
        for (var future : futures) {
            var extraction = future.join();
            var question = extraction.question();
            var relevant = extraction.evidence();
            for (var draft : extraction.drafts()) {
                var status = draft.supported() ? FactStatus.ACCEPTED : FactStatus.REJECTED;
                var supports = relevant.stream().filter(item -> draft.evidenceIds().contains(item.id()))
                        .map(item -> new FactSupport(item.id(), item.sourceStart(), item.sourceEnd())).toList();
                var fact = new FactItem(UUID.randomUUID(), question.id(), draft.statement(), draft.evidenceIds(),
                        draft.confidence(), status, null, supports, draft.supported() ? null : draft.reason());
                facts.add(fact);
                artifacts.saveFact(runId, fact);
                events.publish(runId, draft.supported() ? StreamEventType.FACT_ACCEPTED : StreamEventType.FACT_REJECTED,
                        Map.of("id", fact.id(), "subQuestionId", fact.subQuestionId(),
                                "statement", fact.statement(), "evidenceIds", fact.evidenceIds(),
                                "confidence", fact.confidence(), "status", fact.status(), "reason", draft.reason()));
            }
        }
    }

    private void applyConflicts(UUID runId, UUID profileId, List<FactItem> facts) {
        var accepted = facts.stream().filter(item -> item.status() == FactStatus.ACCEPTED).toList();
        for (var conflict : reasoner.detectConflicts(profileId, accepted)) {
            var groupId = UUID.randomUUID();
            var conflictingIds = new ArrayList<UUID>();
            for (var index : conflict.factIndexes()) {
                var current = accepted.get(index);
                var replacement = new FactItem(
                        current.id(), current.subQuestionId(), current.statement(), current.evidenceIds(),
                        current.confidence(), FactStatus.CONFLICTING, groupId, current.supports(), conflict.reason());
                var position = facts.indexOf(current);
                if (position >= 0) facts.set(position, replacement);
                artifacts.saveFact(runId, replacement);
                conflictingIds.add(replacement.id());
            }
            events.publish(runId, StreamEventType.CONFLICT_DETECTED, Map.of(
                    "conflictGroupId", groupId, "factIds", conflictingIds, "reason", conflict.reason()));
        }
    }

    private String synthesizeEvidence(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            String question,
            String standaloneQuery,
            UUID profileId,
            int timeoutSeconds,
            List<EvidenceItem> evidenceItems,
            Map<UUID, RetrievalHit> evidenceHits,
            int maximumReferences
    ) {
        var evidence = new ArrayList<StreamingAnswerModelPort.AnswerEvidence>();
        var citationHits = new LinkedHashMap<String, RetrievalHit>();
        var seenSpans = new HashSet<String>();
        for (var item : evidenceItems) {
            var spanKey = item.chunkId() + ":" + item.sourceStart() + ":" + item.sourceEnd();
            if (evidence.size() >= maximumReferences || !seenSpans.add(spanKey)) continue;
            var hit = evidenceHits.get(item.id());
            if (hit == null) continue;
            var evidenceId = "E" + (evidence.size() + 1);
            evidence.add(new StreamingAnswerModelPort.AnswerEvidence(
                    evidenceId, hit.documentTitle(), hit.documentVersionId(), hit.chunkId(), item.quote()));
            citationHits.put(evidenceId, hit.withText(item.quote()));
        }
        if (evidence.isEmpty()) {
            return noAnswerForCoverage(runId, conversationId, question, null);
        }
        var personalization = personalization(organizationId, userId);
        if (!personalization.isEmpty()) {
            events.publish(runId, StreamEventType.MEMORY_APPLIED, Map.of(
                    "count", personalization.size(), "purpose", "personalization", "evidenceEligible", false));
        }
        var generation = answerModel.generate(profileId,
                new StreamingAnswerModelPort.AnswerRequest(
                        question, standaloneQuery, evidence, personalization, Math.max(45, timeoutSeconds)),
                delta -> events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", delta)));
        var answer = verifyCitations(runId, organizationId, userId, generation.content(), citationHits);
        memory.append(conversationId, "user", question, runId);
        memory.append(conversationId, "assistant", answer, runId);
        return answer;
    }

    private String renderPartialAnswer(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            String question,
            PartialAnswerPolicy.Decision decision,
            List<EvidenceItem> evidence,
            Map<UUID, RetrievalHit> evidenceHits,
            int maximumReferences,
            CoverageReport coverage
    ) {
        var rendered = buildPartialAnswer(decision, evidence, evidenceHits, maximumReferences);
        if (rendered == null) {
            return noAnswerForCoverage(runId, conversationId, question, coverage);
        }
        var verified = verifyCitations(
                runId, organizationId, userId, rendered.answer(), rendered.citationHits());
        if (!verified.equals(rendered.answer())) {
            return noAnswerForCoverage(runId, conversationId, question, coverage);
        }
        events.publish(runId, StreamEventType.PARTIAL_ANSWER, Map.of(
                "gaps", decision.gaps(), "evidenceCount", rendered.citationHits().size(),
                "rendering", "judge-supported-surfaces"));
        events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", verified));
        memory.append(conversationId, "user", question, runId);
        memory.append(conversationId, "assistant", verified, runId);
        return verified;
    }

    private PartialRenderedAnswer buildPartialAnswer(
            PartialAnswerPolicy.Decision decision,
            List<EvidenceItem> evidence,
            Map<UUID, RetrievalHit> evidenceHits,
            int maximumReferences
    ) {
        var evidenceById = new HashMap<UUID, EvidenceItem>();
        for (var item : evidence) evidenceById.put(item.id(), item);
        var citationIds = new LinkedHashMap<UUID, String>();
        var citationHits = new LinkedHashMap<String, RetrievalHit>();
        var answer = new StringBuilder("现有深读证据支持以下部分结论：");
        for (var section : decision.sections()) {
            var lines = new ArrayList<String>();
            for (var surface : section.surfaces()) {
                var line = renderPartialSurface(
                        surface, evidenceById, evidenceHits, citationIds, citationHits, maximumReferences);
                if (line != null) lines.add(line);
            }
            if (lines.isEmpty()) return null;
            answer.append("\n\n**").append(section.question().question()).append("**\n")
                    .append(String.join("\n", lines));
        }
        answer.append("\n\n证据边界：以下方面尚未被 Evidence Judge 确认：")
                .append(String.join("；", decision.gaps())).append("。");
        return new PartialRenderedAnswer(answer.toString(), citationHits);
    }

    private String renderPartialSurface(
            SupportedSurface surface,
            Map<UUID, EvidenceItem> evidenceById,
            Map<UUID, RetrievalHit> evidenceHits,
            Map<UUID, String> citationIds,
            Map<String, RetrievalHit> citationHits,
            int maximumReferences
    ) {
        var resolved = new LinkedHashMap<UUID, RetrievalHit>();
        int newReferences = 0;
        for (var evidenceId : surface.evidenceIds()) {
            var item = evidenceById.get(evidenceId);
            var hit = evidenceHits.get(evidenceId);
            if (item == null || hit == null) return null;
            resolved.put(evidenceId, hit.withText(item.quote()));
            if (!citationIds.containsKey(evidenceId)) newReferences++;
        }
        if (citationIds.size() + newReferences > maximumReferences) return null;
        var references = new ArrayList<String>();
        for (var entry : resolved.entrySet()) {
            var citationId = citationIds.computeIfAbsent(
                    entry.getKey(), ignored -> "E" + (citationIds.size() + 1));
            citationHits.putIfAbsent(citationId, entry.getValue());
            references.add("[" + citationId + "]");
        }
        return "- " + surface.statement() + " " + String.join("", references);
    }

    private String noAnswerForCoverage(
            UUID runId,
            UUID conversationId,
            String question,
            CoverageReport coverage
    ) {
        var gaps = coverage == null ? List.<String>of() : coverage.items().stream()
                .filter(item -> !item.covered())
                .flatMap(item -> item.gaps().stream())
                .distinct()
                .limit(3)
                .toList();
        var answer = gaps.isEmpty()
                ? "当前知识范围证据不足，无法形成覆盖全部问题的可靠回答。"
                : "当前知识范围证据不足，尚缺少：" + String.join("；", gaps) + "。";
        runRecords.markNoAnswer(runId, "agent-evidence-coverage-insufficient");
        events.publish(runId, StreamEventType.NO_ANSWER, Map.of(
                "reason", "agent-evidence-coverage-insufficient", "gaps", gaps));
        events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", answer));
        memory.append(conversationId, "user", question, runId);
        memory.append(conversationId, "assistant", answer, runId);
        return answer;
    }

    private String synthesize(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            String question,
            UUID profileId,
            int timeoutSeconds,
            CoverageReport coverage,
            List<FactItem> facts,
            Map<UUID, RetrievalHit> evidenceHits
    ) {
        var accepted = facts.stream().filter(item -> item.status() == FactStatus.ACCEPTED).toList();
        if (accepted.isEmpty()) {
            var noAnswer = "当前知识范围没有形成通过证据蕴含审核的事实，无法给出可靠结论。";
            runRecords.markNoAnswer(runId, "no-accepted-agent-facts");
            events.publish(runId, StreamEventType.NO_ANSWER, Map.of("reason", "no-accepted-agent-facts"));
            events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", noAnswer));
            memory.append(conversationId, "user", question, runId);
            memory.append(conversationId, "assistant", noAnswer, runId);
            return noAnswer;
        }

        var evidence = new ArrayList<StreamingAnswerModelPort.AnswerEvidence>();
        var citationHits = new LinkedHashMap<String, RetrievalHit>();
        for (var fact : accepted) {
            var hit = fact.evidenceIds().stream().map(evidenceHits::get).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            if (hit == null) continue;
            var evidenceId = "E" + (evidence.size() + 1);
            evidence.add(new StreamingAnswerModelPort.AnswerEvidence(
                    evidenceId, hit.documentTitle(), hit.documentVersionId(), hit.chunkId(), fact.statement()));
            citationHits.put(evidenceId, hit);
        }
        if (evidence.isEmpty()) throw new IllegalStateException("Accepted Agent facts have no resolvable evidence");
        var personalization = personalization(organizationId, userId);
        if (!personalization.isEmpty()) {
            events.publish(runId, StreamEventType.MEMORY_APPLIED, Map.of(
                    "count", personalization.size(), "purpose", "personalization", "evidenceEligible", false));
        }
        var generation = answerModel.generate(profileId,
                new StreamingAnswerModelPort.AnswerRequest(
                        question, question, evidence, personalization,
                        Math.max(45, timeoutSeconds)),
                delta -> events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", delta)));
        var answer = verifyCitations(runId, organizationId, userId, generation.content(), citationHits);
        var disclosure = coverageDisclosure(coverage);
        if (!disclosure.isEmpty()) {
            events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", disclosure));
            answer += disclosure;
        }
        memory.append(conversationId, "user", question, runId);
        memory.append(conversationId, "assistant", answer, runId);
        return answer;
    }

    private String coverageDisclosure(CoverageReport coverage) {
        if (coverage == null) {
            return "\n\n证据覆盖说明：本次运行没有形成完整覆盖报告；以上仅陈述已通过审核的事实。";
        }
        long conflicts = coverage.items().stream().filter(item -> item.hasConflict()).count();
        long gaps = coverage.items().stream().filter(item -> !item.covered() && !item.hasConflict()).count();
        if (conflicts == 0 && gaps == 0) return "";
        var details = new ArrayList<String>();
        if (gaps > 0) details.add(gaps + " 个扩展子问题仍缺少充分证据");
        if (conflicts > 0) details.add(conflicts + " 个扩展子问题仍有事实冲突");
        return "\n\n证据覆盖说明：" + String.join("，", details)
                + "；以上仅陈述已通过审核的事实，具体缺口可在研究轨迹中查看。";
    }

    private List<String> personalization(UUID organizationId, UUID userId) {
        if (userId == null) return List.of();
        return memoryFacts.findConfirmedActive(organizationId, userId, clock.instant(), 20).stream()
                .map(com.yanyue.rag.domain.model.MemoryFact::factText)
                .toList();
    }

    private boolean shouldRewrite(String query, List<String> recent) {
        if (recent.isEmpty()) return false;
        return query.length() < 36 || query.matches(".*(它|这个|上面|刚才|其|该|他们|those|that|it).*?");
    }

    private String verifyCitations(
            UUID runId,
            UUID organizationId,
            UUID userId,
            String answer,
            Map<String, RetrievalHit> evidence
    ) {
        var referenced = new java.util.LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern.compile("\\[E(\\d+)]").matcher(answer);
        while (matcher.find()) referenced.add("E" + matcher.group(1));
        var verified = answer;
        for (var evidenceId : referenced) {
            var hit = evidence.get(evidenceId);
            var valid = hit != null && citationValidation.isCurrentlyValid(
                    organizationId, userId, hit, clock.instant());
            events.publish(runId, StreamEventType.CITATION_VERIFIED, Map.of(
                    "evidenceId", evidenceId, "valid", valid,
                    "reason", valid ? "accepted-fact-current-evidence" : "unknown-or-no-longer-effective"));
            if (!valid) {
                verified = verified.replace("[" + evidenceId + "]", "");
                continue;
            }
            var index = Integer.parseInt(evidenceId.substring(1));
            citations.save(runId, index, hit);
            var citationPayload = new LinkedHashMap<String, Object>();
            citationPayload.put("index", index);
            citationPayload.put("evidenceId", evidenceId);
            citationPayload.put("chunkId", hit.chunkId());
            citationPayload.put("documentId", hit.documentId());
            citationPayload.put("documentVersionId", hit.documentVersionId());
            citationPayload.put("documentTitle", hit.documentTitle());
            citationPayload.put("quote", hit.text());
            if (hit.pageNumber() != null) citationPayload.put("pageNumber", hit.pageNumber());
            if (hit.sourceStart() != null) citationPayload.put("sourceStart", hit.sourceStart());
            if (hit.sourceEnd() != null) citationPayload.put("sourceEnd", hit.sourceEnd());
            events.publish(runId, StreamEventType.CITATION, citationPayload);
        }
        return verified;
    }

    private void move(AgentRunState state, AgentStage stage, QuestionPlan plan) {
        telemetry.observe("rag.agent.stage", Map.of("stage", stage.name()), () -> {
            state.moveTo(stage, clock.instant());
            artifacts.checkpoint(state, plan);
            return stage;
        });
    }

    private void publishBudget(UUID runId, AgentRunState state, String reason) {
        events.publish(runId, StreamEventType.BUDGET_UPDATED, Map.of(
                "reason", reason,
                "roundsUsed", state.budget().roundsUsed(), "maxRounds", state.budget().maxRounds(),
                "searchesUsed", state.budget().searchesUsed(), "maxSearches", state.budget().maxSearches(),
                "deepReadsUsed", state.budget().deepReadsUsed(), "maxDeepReads", state.budget().maxDeepReads(),
                "timedOut", state.budget().timedOut(clock.instant())));
    }

    private void checkCancelled(UUID runId, AgentRunState state) {
        if (Thread.currentThread().isInterrupted() || runRecords.isCancellationRequested(runId)) {
            if (state.stage() != AgentStage.CANCELLED && state.stage() != AgentStage.COMPLETED) {
                try {
                    state.moveTo(AgentStage.CANCELLED, clock.instant());
                    artifacts.checkpoint(state, null);
                } catch (IllegalStateException ignored) {
                    // The persisted Run cancellation remains authoritative if a stage has already closed.
                }
            }
            throw new java.util.concurrent.CancellationException("Agent Run was cancelled");
        }
    }

    private List<Map<String, Object>> summaries(List<RetrievalHit> hits, int limit) {
        return hits.stream().limit(limit).map(hit -> Map.<String, Object>of(
                "chunkId", hit.chunkId(), "documentTitle", hit.documentTitle(), "score", hit.score(),
                "sources", hit.sources(),
                "preview", hit.text().substring(0, Math.min(120, hit.text().length())))).toList();
    }

    private List<String> append(List<String> values, String value) {
        var copy = new ArrayList<>(values);
        if (!copy.contains(value)) copy.add(value);
        return List.copyOf(copy);
    }

    private String safeMessage(Throwable failure) {
        var value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.substring(0, Math.min(500, value.length()));
    }

    private record TaskOutcome(RetrievalTask task, List<RetrievalHit> hits, boolean failed) {
    }

    private record EvidenceExtractionRequest(
            UUID subQuestionId,
            int contextCount,
            String question,
            List<AgentStructuredReasoner.EvidenceContext> contexts
    ) {
    }

    private record PartialRenderedAnswer(String answer, Map<String, RetrievalHit> citationHits) {
        private PartialRenderedAnswer {
            citationHits = Map.copyOf(citationHits);
        }
    }

    private record FactExtraction(
            SubQuestion question,
            List<EvidenceItem> evidence,
            List<AgentStructuredReasoner.FactDraft> drafts
    ) {
    }
}
