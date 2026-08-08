package com.yanyue.rag.application.evaluation;

import com.yanyue.rag.application.chat.ReciprocalRankFusion;
import com.yanyue.rag.application.chat.RunCoordinator;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.evaluation.CreateEvaluationCaseRequest;
import com.yanyue.rag.contract.evaluation.CreateEvaluationDatasetRequest;
import com.yanyue.rag.contract.evaluation.EvaluationCaseView;
import com.yanyue.rag.contract.evaluation.EvaluationComparisonDetailView;
import com.yanyue.rag.contract.evaluation.EvaluationComparisonView;
import com.yanyue.rag.contract.evaluation.EvaluationDatasetDetailView;
import com.yanyue.rag.contract.evaluation.EvaluationDatasetBundle;
import com.yanyue.rag.contract.evaluation.EvaluationDatasetView;
import com.yanyue.rag.contract.evaluation.EvaluationJudgeMode;
import com.yanyue.rag.contract.evaluation.EvaluationResultView;
import com.yanyue.rag.contract.evaluation.EvaluationRunDetailView;
import com.yanyue.rag.contract.evaluation.EvaluationRunView;
import com.yanyue.rag.contract.evaluation.EvaluationRunSummaryView;
import com.yanyue.rag.contract.evaluation.StartEvaluationRunRequest;
import com.yanyue.rag.contract.evaluation.StartEvaluationComparisonRequest;
import com.yanyue.rag.domain.evaluation.EvaluationCase;
import com.yanyue.rag.domain.evaluation.EvaluationCaseAttempt;
import com.yanyue.rag.domain.evaluation.EvaluationComparison;
import com.yanyue.rag.domain.evaluation.EvaluationDataset;
import com.yanyue.rag.domain.evaluation.EvaluationRun;
import com.yanyue.rag.domain.port.EvaluationRepository;
import com.yanyue.rag.domain.port.EvaluationAttemptPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EvaluationService {
    private static final String CONVERSATION_GROUP = "conversationGroup";
    private static final String CONVERSATION_TURN = "conversationTurn";
    private static final Duration CASE_TIMEOUT = Duration.ofMinutes(15);

    private final EvaluationRepository repository;
    private final RetrievalPort retrieval;
    private final Executor executor;
    private final Clock clock;
    private final RunCoordinator runCoordinator;
    private final EvaluationJudge judge;
    private final EvaluationAttemptPort attempts;
    private final ObjectMapper objectMapper;
    private final int agenticRetrievalParallelism;
    private final Map<UUID, FutureTask<Void>> activeEvaluationRuns = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Set<UUID>> activeRagRuns = new ConcurrentHashMap<>();

    @Autowired
    public EvaluationService(EvaluationRepository repository, RetrievalPort retrieval,
                             @Qualifier("ragRunExecutor") Executor executor, Clock clock,
                             RunCoordinator runCoordinator, EvaluationJudge judge,
                             EvaluationAttemptPort attempts,
                             ObjectMapper objectMapper,
                             @Value("${rag.evaluation.agentic-retrieval-parallelism:2}")
                             int agenticRetrievalParallelism) {
        this.repository = repository;
        this.retrieval = retrieval;
        this.executor = executor;
        this.clock = clock;
        this.runCoordinator = runCoordinator;
        this.judge = judge;
        this.attempts = attempts;
        this.objectMapper = objectMapper;
        if (agenticRetrievalParallelism < 1 || agenticRetrievalParallelism > 64) {
            throw new IllegalArgumentException("Agentic retrieval parallelism must be between 1 and 64");
        }
        this.agenticRetrievalParallelism = agenticRetrievalParallelism;
    }

    EvaluationService(EvaluationRepository repository, RetrievalPort retrieval,
                      Executor executor, Clock clock, RunCoordinator runCoordinator, EvaluationJudge judge,
                      EvaluationAttemptPort attempts, int agenticRetrievalParallelism) {
        this(repository, retrieval, executor, clock, runCoordinator, judge, attempts,
                new ObjectMapper(), agenticRetrievalParallelism);
    }

    EvaluationService(EvaluationRepository repository, RetrievalPort retrieval,
                      Executor executor, Clock clock, RunCoordinator runCoordinator, EvaluationJudge judge) {
        this(repository, retrieval, executor, clock, runCoordinator, judge, null, new ObjectMapper(), 2);
    }

    EvaluationService(EvaluationRepository repository, RetrievalPort retrieval,
                      Executor executor, Clock clock, RunCoordinator runCoordinator) {
        this(repository, retrieval, executor, clock, runCoordinator, null, null, 2);
    }

    EvaluationService(EvaluationRepository repository, RetrievalPort retrieval,
                      Executor executor, Clock clock) {
        this.repository = repository;
        this.retrieval = retrieval;
        this.executor = executor;
        this.clock = clock;
        this.runCoordinator = null;
        this.judge = null;
        this.attempts = null;
        this.objectMapper = new ObjectMapper();
        this.agenticRetrievalParallelism = 2;
    }

    public List<EvaluationDatasetView> listDatasets(UUID organizationId) {
        return repository.findDatasets(organizationId).stream()
                .map(dataset -> datasetView(organizationId, dataset))
                .toList();
    }

    public List<EvaluationRunSummaryView> listRuns(UUID organizationId, int limit) {
        var datasets = repository.findDatasets(organizationId).stream()
                .collect(java.util.stream.Collectors.toMap(EvaluationDataset::id, value -> value));
        return repository.findRuns(organizationId, limit).stream().map(run -> {
            var dataset = datasets.get(run.datasetId());
            var results = repository.findResults(organizationId, run.id());
            var total = number(run.aggregateMetrics().get("caseCount"));
            if (total == 0 && dataset != null) total = repository.findCases(organizationId, dataset.id()).size();
            var failed = results.stream().filter(result -> result.errorMessage() != null).count();
            var mode = String.valueOf(run.aggregateMetrics().getOrDefault("requestedMode", "AUTO"));
            var datasetName = dataset == null ? "已删除的数据集" : dataset.name();
            var name = datasetName + " · " + mode + " · "
                    + java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault()).format(run.createdAt());
            return new EvaluationRunSummaryView(
                    run.id(), run.datasetId(), name, datasetName, run.status().name(), mode,
                    Math.toIntExact(total), results.size(), Math.toIntExact(failed),
                    run.startedAt(), run.completedAt(), run.createdAt());
        }).toList();
    }

    public EvaluationDatasetView createDataset(UUID organizationId, CreateEvaluationDatasetRequest request) {
        return datasetView(organizationId, repository.createDataset(
                organizationId, request.name().strip(), request.description().strip()));
    }

    public EvaluationDatasetBundle exportDataset(UUID organizationId, UUID datasetId) {
        var dataset = requireDataset(organizationId, datasetId);
        var cases = repository.findCases(organizationId, datasetId).stream().map(value ->
                new EvaluationDatasetBundle.CaseEntry(
                        value.question(), value.expectedAnswer(), value.expectedDocumentIds(), value.metadata()))
                .toList();
        if (cases.isEmpty()) throw new IllegalArgumentException("Cannot export an empty evaluation dataset");
        return new EvaluationDatasetBundle(
                EvaluationDatasetBundle.SCHEMA_VERSION, dataset.id(), clock.instant(), dataset.name(),
                dataset.description(), cases);
    }

    @Transactional
    public EvaluationDatasetDetailView importDataset(
            UUID organizationId,
            EvaluationDatasetBundle bundle
    ) {
        validateBundle(organizationId, bundle);
        var dataset = repository.createDataset(
                organizationId, bundle.name().strip(), bundle.description().strip());
        for (var entry : bundle.cases()) {
            repository.addCase(
                    organizationId, dataset.id(), entry.question().strip(),
                    entry.expectedAnswer() == null ? null : entry.expectedAnswer().strip(),
                    entry.expectedDocumentIds(), entry.metadata());
        }
        return dataset(organizationId, dataset.id());
    }

    public EvaluationDatasetDetailView dataset(UUID organizationId, UUID datasetId) {
        var dataset = requireDataset(organizationId, datasetId);
        var cases = repository.findCases(organizationId, datasetId);
        var runs = repository.findRuns(organizationId, datasetId);
        return new EvaluationDatasetDetailView(
                datasetView(dataset, cases.size(), runs),
                cases.stream().map(this::caseView).toList(),
                runs.stream().map(this::runView).toList()
        );
    }

    public EvaluationCaseView addCase(UUID organizationId, UUID datasetId,
                                      CreateEvaluationCaseRequest request) {
        requireDataset(organizationId, datasetId);
        validateOwnedDocuments(organizationId, request.expectedDocumentIds());
        var existingCases = repository.findCases(organizationId, datasetId);
        var candidate = new EvaluationCase(
                UUID.randomUUID(), datasetId, request.question(), request.expectedAnswer(),
                request.expectedDocumentIds(), request.metadata(), Long.MAX_VALUE);
        var prospectiveCases = new ArrayList<>(existingCases);
        prospectiveCases.add(candidate);
        validateConversationCases(prospectiveCases);
        return caseView(repository.addCase(
                organizationId,
                datasetId,
                request.question().strip(),
                request.expectedAnswer() == null ? null : request.expectedAnswer().strip(),
                request.expectedDocumentIds(),
                request.metadata()
        ));
    }

    public void deleteCase(UUID organizationId, UUID datasetId, UUID caseId) {
        var cases = repository.findCases(organizationId, datasetId);
        var target = cases.stream().filter(value -> value.id().equals(caseId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Evaluation case not found"));
        var targetConversation = conversationSpec(target.metadata());
        if (targetConversation != null && cases.stream()
                .map(EvaluationCase::metadata)
                .map(this::conversationSpec)
                .anyMatch(value -> value != null
                        && value.group().equals(targetConversation.group())
                        && value.turn() > targetConversation.turn())) {
            throw new IllegalArgumentException("Delete later conversation turns before this case");
        }
        if (!repository.deleteCase(organizationId, datasetId, caseId)) {
            throw new IllegalArgumentException("Evaluation case not found");
        }
    }

    public EvaluationRunView startRun(UUID organizationId, UUID datasetId) {
        return startRetrievalRun(organizationId, datasetId, StartEvaluationRunRequest.defaults());
    }

    public EvaluationRunView startRetrievalRun(
            UUID organizationId,
            UUID datasetId,
            StartEvaluationRunRequest request
    ) {
        requireDataset(organizationId, datasetId);
        requireCases(organizationId, datasetId);
        var initial = new LinkedHashMap<String, Object>();
        initial.put("execution", "RETRIEVAL_ONLY");
        initial.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
        initial.put("documentIds", request.scope().documentIds());
        initial.put("metadataFilterCount", request.filters().size());
        var run = repository.createRun(organizationId, datasetId, Map.copyOf(initial));
        saveRequestSnapshot(run.id(), request, "RETRIEVAL_ONLY");
        dispatch(run.id(), () -> executeRetrieval(organizationId, run.id(), datasetId, request));
        return runView(run);
    }

    public EvaluationRunView startRagRun(
            UUID organizationId,
            UUID userId,
            UUID datasetId,
            StartEvaluationRunRequest request
    ) {
        requireDataset(organizationId, datasetId);
        requireCases(organizationId, datasetId);
        if (runCoordinator == null) throw new IllegalStateException("RAG evaluation runtime is unavailable");
        var run = createQueuedRagRun(organizationId, datasetId, request);
        saveRequestSnapshot(run.id(), request, "RAG");
        dispatch(run.id(), () -> executeRag(organizationId, userId, run.id(), datasetId, request, false));
        return runView(run);
    }

    public EvaluationRunView startRoutingRun(
            UUID organizationId,
            UUID datasetId,
            StartEvaluationRunRequest request
    ) {
        requireDataset(organizationId, datasetId);
        requireCases(organizationId, datasetId);
        if (runCoordinator == null) throw new IllegalStateException("Routing evaluation runtime is unavailable");
        var autoRequest = new StartEvaluationRunRequest(
                RunMode.AUTO, request.scope(), request.filters(), request.modelProfileId(), EvaluationJudgeMode.NONE);
        var run = createQueuedRoutingRun(organizationId, datasetId, autoRequest);
        saveRequestSnapshot(run.id(), autoRequest, "ROUTING_ONLY");
        dispatch(run.id(), () -> executeRouting(organizationId, run.id(), datasetId, autoRequest));
        return runView(run);
    }

    public EvaluationRunView startAgenticRetrievalRun(
            UUID organizationId,
            UUID userId,
            UUID datasetId,
            StartEvaluationRunRequest request
    ) {
        requireDataset(organizationId, datasetId);
        requireCases(organizationId, datasetId);
        if (runCoordinator == null) throw new IllegalStateException("RAG evaluation runtime is unavailable");
        var deepRequest = new StartEvaluationRunRequest(
                RunMode.DEEP, request.scope(), request.filters(), request.modelProfileId(), EvaluationJudgeMode.NONE);
        var run = createQueuedAgenticRetrievalRun(organizationId, datasetId, deepRequest);
        saveRequestSnapshot(run.id(), deepRequest, "AGENTIC_RETRIEVAL_ONLY");
        dispatch(run.id(), () -> executeRag(
                organizationId, userId, run.id(), datasetId, deepRequest, true));
        return runView(run);
    }

    @Transactional
    public EvaluationRunView resumeRun(
            UUID organizationId,
            UUID userId,
            UUID runId,
            StartEvaluationRunRequest request
    ) {
        var previous = repository.findRun(organizationId, runId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation run not found"));
        if (previous.status() == com.yanyue.rag.domain.evaluation.EvaluationRunStatus.QUEUED
                || previous.status() == com.yanyue.rag.domain.evaluation.EvaluationRunStatus.RUNNING) {
            throw new IllegalArgumentException("Only terminal evaluation runs can be resumed");
        }
        if (attempts == null) throw new IllegalStateException("Evaluation resume runtime is unavailable");
        var lineage = attempts.loadLineage(previous.id())
                .orElseThrow(() -> new IllegalArgumentException("Evaluation run has no resumable request snapshot"));
        var execution = String.valueOf(lineage.requestSnapshot().getOrDefault("execution", ""));
        if (!java.util.Set.of("RAG", "AGENTIC_RETRIEVAL_ONLY", "ROUTING_ONLY").contains(execution)) {
            throw new IllegalArgumentException("Evaluation execution cannot be resumed incrementally: " + execution);
        }
        var originalRequest = requestFromSnapshot(lineage.requestSnapshot());
        validateResumableExecution(execution, originalRequest);
        if (request != null && !sameRequestSnapshot(
                requestSnapshot(request, execution), lineage.requestSnapshot())) {
            throw new IllegalArgumentException("A resumed evaluation must use the original request configuration");
        }
        var nextRequest = originalRequest;
        var next = switch (execution) {
            case "RAG" -> createQueuedRagRun(organizationId, previous.datasetId(), nextRequest);
            case "AGENTIC_RETRIEVAL_ONLY" -> createQueuedAgenticRetrievalRun(
                    organizationId, previous.datasetId(), nextRequest);
            case "ROUTING_ONLY" -> createQueuedRoutingRun(organizationId, previous.datasetId(), nextRequest);
            default -> throw new IllegalStateException("Unsupported resume execution");
        };
        attempts.linkResumedRun(next.id(), previous.id(), requestSnapshot(nextRequest, execution));
        afterCommit(() -> dispatch(next.id(), () -> executeResumedRun(
                organizationId, userId, next.id(), previous.id(), previous.datasetId(), nextRequest, execution)));
        return runView(next);
    }

    public void cancelRun(UUID organizationId, UUID runId) {
        repository.findRun(organizationId, runId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation run not found"));
        boolean persisted = repository.cancelRun(organizationId, runId);
        var task = activeEvaluationRuns.get(runId);
        if (!persisted && task == null) return;
        for (var ragRunId : List.copyOf(activeRagRuns.getOrDefault(runId, java.util.Set.of()))) {
            if (runCoordinator != null) runCoordinator.cancel(ragRunId);
        }
        if (task != null) task.cancel(true);
    }

    private void dispatch(UUID evaluationRunId, Runnable action) {
        var reference = new AtomicReference<FutureTask<Void>>();
        var task = new FutureTask<Void>(() -> {
            try {
                action.run();
            } finally {
                activeEvaluationRuns.remove(evaluationRunId, reference.get());
                activeRagRuns.remove(evaluationRunId);
            }
            return null;
        });
        reference.set(task);
        if (activeEvaluationRuns.putIfAbsent(evaluationRunId, task) != null) {
            throw new IllegalStateException("Evaluation run is already active");
        }
        executor.execute(task);
    }

    private void ensureNotCancelled(UUID evaluationRunId) {
        if (Thread.currentThread().isInterrupted()
                || repository.isRunCancellationRequested(evaluationRunId)) {
            throw new CancellationException("Evaluation run was cancelled");
        }
    }

    private void trackRagRun(UUID evaluationRunId, UUID ragRunId) {
        activeRagRuns.computeIfAbsent(evaluationRunId, ignored -> ConcurrentHashMap.newKeySet()).add(ragRunId);
        try {
            ensureNotCancelled(evaluationRunId);
        } catch (CancellationException exception) {
            if (runCoordinator != null) runCoordinator.cancel(ragRunId);
            untrackRagRun(evaluationRunId, ragRunId);
            throw exception;
        }
    }

    private void untrackRagRun(UUID evaluationRunId, UUID ragRunId) {
        var runs = activeRagRuns.get(evaluationRunId);
        if (runs == null) return;
        runs.remove(ragRunId);
        if (runs.isEmpty()) activeRagRuns.remove(evaluationRunId, runs);
    }

    private void cancelActiveRagRuns(UUID evaluationRunId) {
        for (var ragRunId : List.copyOf(activeRagRuns.getOrDefault(evaluationRunId, java.util.Set.of()))) {
            if (runCoordinator != null) runCoordinator.cancel(ragRunId);
        }
    }

    private void handleCancellation(UUID evaluationRunId, CancellationException exception) {
        cancelActiveRagRuns(evaluationRunId);
        if (!repository.isRunCancellationRequested(evaluationRunId)) {
            repository.failRun(evaluationRunId, message(exception));
        }
    }

    private StartEvaluationRunRequest requestFromSnapshot(Map<String, Object> snapshot) {
        try {
            var mode = RunMode.valueOf(String.valueOf(snapshot.get("mode")));
            var scope = new KnowledgeScope(
                    uuidList(snapshot.get("knowledgeBaseIds")), uuidList(snapshot.get("documentIds")));
            var filters = objectMapper.convertValue(
                    snapshot.getOrDefault("filters", List.of()), new TypeReference<List<MetadataFilter>>() { });
            var profile = snapshot.get("modelProfileId") == null
                    ? null : UUID.fromString(String.valueOf(snapshot.get("modelProfileId")));
            var judgeMode = EvaluationJudgeMode.valueOf(String.valueOf(snapshot.get("judgeMode")));
            return new StartEvaluationRunRequest(mode, scope, filters, profile, judgeMode);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Evaluation request snapshot is invalid", exception);
        }
    }

    private void validateResumableExecution(String execution, StartEvaluationRunRequest request) {
        boolean resumable = switch (execution) {
            case "AGENTIC_RETRIEVAL_ONLY" -> request.mode() == RunMode.DEEP;
            case "RAG" -> request.mode() == RunMode.DEEP || request.mode() == RunMode.AUTO;
            case "ROUTING_ONLY" -> request.mode() == RunMode.AUTO;
            default -> false;
        };
        if (!resumable) {
            throw new IllegalArgumentException(
                    "Evaluation execution and mode cannot be resumed incrementally: "
                            + execution + "/" + request.mode());
        }
    }

    private boolean sameRequestSnapshot(Map<String, Object> left, Map<String, Object> right) {
        return objectMapper.valueToTree(left).equals(objectMapper.valueToTree(right));
    }

    private List<UUID> uuidList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).map(UUID::fromString).toList();
    }

    boolean reusableRagResult(
            com.yanyue.rag.domain.evaluation.EvaluationResult result,
            String expectedExecution
    ) {
        if (result == null || result.errorMessage() != null || result.metrics().isEmpty()) return false;
        var metrics = result.metrics();
        if (!expectedExecution.equals(String.valueOf(metrics.getOrDefault("execution", "")))) return false;
        if (!isRouteMode(metrics.get("selectedMode"))) return false;
        if ("FAILED".equals(metrics.get("judgeStatus"))) return false;
        if (number(metrics.get("toolFailureCount")) > 0) return false;
        if (RunMode.DEEP.name().equals(metrics.get("selectedMode"))) {
            if (!(metrics.get("runtimeSnapshot") instanceof Map<?, ?> runtime) || runtime.isEmpty()) return false;
            var pipelineVersion = String.valueOf(runtime.get("pipelineVersion"));
            if (java.util.Set.of("agentic-rag-v4", "agentic-rag-v5", "agentic-rag-v7", "agentic-rag-v8")
                    .contains(pipelineVersion)) {
                return reusableControlledAgenticResult(metrics, expectedExecution, pipelineVersion);
            }
        }
        var diagnostics = metrics.get("toolDiagnostics");
        if (!(diagnostics instanceof Map<?, ?> values)) {
            return !RunMode.DEEP.name().equals(metrics.get("selectedMode"));
        }
        if (number(values.get("deepReadFailureCount")) > 0) return false;
        var deepRead = values.get("tool.deep_read");
        if (deepRead instanceof Map<?, ?> tool && number(tool.get("failed")) > 0) return false;
        if (number(values.get("evidenceJudgeFailureCount")) > 0) return false;
        var judge = values.get("tool.evidence_judge");
        if (judge instanceof Map<?, ?> tool && number(tool.get("failed")) > 0) return false;
        return !RunMode.DEEP.name().equals(metrics.get("selectedMode"))
                || judge instanceof Map<?, ?> tool && number(tool.get("calls")) > 0;
    }

    private boolean reusableControlledAgenticResult(
            Map<String, Object> metrics,
            String expectedExecution,
            String pipelineVersion
    ) {
        if (!(metrics.get("toolDiagnostics") instanceof Map<?, ?> diagnostics)) return false;
        if (number(diagnostics.get("failedSupportActionCount")) > 0
                || failedModelLogicalCalls(diagnostics) > 0
                || number(diagnostics.get("hiddenEvidenceOutcomeCount")) > 0
                || !validEvidenceJudgeTopology(pipelineVersion, diagnostics)) {
            return false;
        }
        if ("AGENTIC_RETRIEVAL_ONLY".equals(expectedExecution)) return true;
        var stopReason = String.valueOf(diagnostics.get("stopReason"));
        return java.util.Set.of("COMPLETED_WITH_EVIDENCE", "ZERO_ACCEPTED_EVIDENCE").contains(stopReason);
    }

    private Map<String, Object> reusedMetrics(Map<String, Object> metrics, UUID sourceRunId) {
        var reused = new LinkedHashMap<String, Object>(metrics);
        reused.put("reusedSuccessfulResult", true);
        reused.put("reusedFromEvaluationRunId", sourceRunId);
        return Map.copyOf(reused);
    }

    private void executeResumedRun(
            UUID organizationId,
            UUID userId,
            UUID evaluationRunId,
            UUID previousRunId,
            UUID datasetId,
            StartEvaluationRunRequest request,
            String execution
    ) {
        try {
            repository.markRunRunning(evaluationRunId);
            ensureNotCancelled(evaluationRunId);
            var allCases = repository.findCases(organizationId, datasetId);
            validateConversationCases(allCases);
            if (allCases.stream().anyMatch(value -> conversationSpec(value.metadata()) != null)) {
                throw new IllegalArgumentException("Conversation evaluation runs cannot be resumed incrementally");
            }
            var previous = repository.findResults(organizationId, previousRunId).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            com.yanyue.rag.domain.evaluation.EvaluationResult::evaluationCaseId,
                            value -> value, (first, ignored) -> first));
            if ("ROUTING_ONLY".equals(execution)) {
                executeResumedRouting(organizationId, evaluationRunId, request, allCases, previous, previousRunId);
                return;
            }
            boolean retrievalOnly = "AGENTIC_RETRIEVAL_ONLY".equals(execution);
            var completed = new ArrayList<CaseMetrics>();
            var pending = new ArrayList<EvaluationCase>();
            for (var evaluationCase : allCases) {
                var result = previous.get(evaluationCase.id());
                if (reusableRagResult(result, execution)) {
                    var reusedMetrics = reusedMetrics(result.metrics(), previousRunId);
                    repository.saveResult(evaluationRunId, evaluationCase.id(), result.ragRunId(),
                            reusedMetrics, null);
                    completed.add(storedMetrics(evaluationCase, reusedMetrics));
                } else {
                    pending.add(evaluationCase);
                }
            }
            executeIndependentRagCases(organizationId, userId, evaluationRunId, request,
                    retrievalOnly, allCases.size(), pending, completed, completed.size());
        } catch (CancellationException exception) {
            handleCancellation(evaluationRunId, exception);
        } catch (Exception exception) {
            repository.failRun(evaluationRunId, message(exception));
        }
    }

    @Transactional
    public EvaluationComparisonView startComparison(
            UUID organizationId,
            UUID userId,
            UUID datasetId,
            StartEvaluationComparisonRequest request
    ) {
        requireDataset(organizationId, datasetId);
        requireCases(organizationId, datasetId);
        if (runCoordinator == null) throw new IllegalStateException("RAG evaluation runtime is unavailable");
        var fastRequest = new StartEvaluationRunRequest(
                RunMode.FAST, request.scope(), request.filters(), request.modelProfileId(), request.judgeMode());
        var deepRequest = new StartEvaluationRunRequest(
                RunMode.DEEP, request.scope(), request.filters(), request.modelProfileId(), request.judgeMode());
        var fastRun = createQueuedRagRun(organizationId, datasetId, fastRequest);
        var deepRun = createQueuedRagRun(organizationId, datasetId, deepRequest);
        saveRequestSnapshot(fastRun.id(), fastRequest, "RAG");
        saveRequestSnapshot(deepRun.id(), deepRequest, "RAG");
        var comparison = repository.createComparison(
                organizationId, userId, datasetId, fastRun.id(), deepRun.id(), request.judgeMode().name());
        afterCommit(() -> {
            dispatch(fastRun.id(), () -> executeRag(
                    organizationId, userId, fastRun.id(), datasetId, fastRequest, false));
            dispatch(deepRun.id(), () -> executeRag(
                    organizationId, userId, deepRun.id(), datasetId, deepRequest, false));
        });
        return comparisonView(comparison, fastRun, deepRun);
    }

    public EvaluationComparisonDetailView comparison(UUID organizationId, UUID comparisonId) {
        var comparison = repository.findComparison(organizationId, comparisonId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation comparison not found"));
        var fast = run(organizationId, comparison.fastRunId());
        var deep = run(organizationId, comparison.deepRunId());
        return new EvaluationComparisonDetailView(
                comparisonView(comparison, fast.run(), deep.run()), fast, deep);
    }

    public EvaluationRunDetailView run(UUID organizationId, UUID runId) {
        var run = repository.findRun(organizationId, runId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation run not found"));
        var cases = repository.findCases(organizationId, run.datasetId()).stream()
                .collect(java.util.stream.Collectors.toMap(EvaluationCase::id, value -> value));
        var results = repository.findResults(organizationId, runId).stream()
                .map(result -> new EvaluationResultView(
                        result.id(),
                        result.evaluationCaseId(),
                        result.ragRunId(),
                        cases.containsKey(result.evaluationCaseId())
                                ? cases.get(result.evaluationCaseId()).question() : "已删除的评测样例",
                        cases.containsKey(result.evaluationCaseId())
                                ? cases.get(result.evaluationCaseId()).expectedAnswer() : null,
                        cases.containsKey(result.evaluationCaseId())
                                ? cases.get(result.evaluationCaseId()).expectedDocumentIds() : List.of(),
                        cases.containsKey(result.evaluationCaseId())
                                ? cases.get(result.evaluationCaseId()).metadata() : Map.of(),
                        result.metrics(),
                        result.errorMessage(),
                        result.createdAt()
                ))
                .toList();
        var dataset = requireDataset(organizationId, run.datasetId());
        var snapshot = attempts == null ? Map.<String, Object>of()
                : attempts.loadLineage(runId).map(value -> value.requestSnapshot()).orElse(Map.of());
        return new EvaluationRunDetailView(runView(run), datasetView(organizationId, dataset), snapshot, results);
    }

    private void executeRetrieval(
            UUID organizationId,
            UUID runId,
            UUID datasetId,
            StartEvaluationRunRequest request
    ) {
        try {
            repository.markRunRunning(runId);
            ensureNotCancelled(runId);
            var cases = repository.findCases(organizationId, datasetId);
            var scope = RetrievalScope.system(
                    organizationId,
                    request.scope().knowledgeBaseIds(),
                    request.scope().documentIds(),
                    request.filters(),
                    clock.instant());
            var completed = new ArrayList<CaseMetrics>();
            int failures = 0;
            for (var evaluationCase : cases) {
                ensureNotCancelled(runId);
                try {
                    var metrics = evaluateCase(scope, evaluationCase);
                    repository.saveResult(runId, evaluationCase.id(), metrics.values(), null);
                    completed.add(metrics);
                } catch (Exception exception) {
                    failures++;
                    repository.saveResult(runId, evaluationCase.id(), Map.of(),
                            message(exception));
                }
            }
            var aggregate = new LinkedHashMap<>(aggregate(cases.size(), failures, completed));
            aggregate.put("execution", "RETRIEVAL_ONLY");
            aggregate.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
            aggregate.put("documentIds", request.scope().documentIds());
            aggregate.put("metadataFilterCount", request.filters().size());
            repository.completeRun(runId, Map.copyOf(aggregate));
        } catch (CancellationException exception) {
            handleCancellation(runId, exception);
        } catch (Exception exception) {
            repository.failRun(runId, message(exception));
        }
    }

    private void executeRouting(
            UUID organizationId,
            UUID evaluationRunId,
            UUID datasetId,
            StartEvaluationRunRequest request
    ) {
        try {
            repository.markRunRunning(evaluationRunId);
            ensureNotCancelled(evaluationRunId);
            var cases = repository.findCases(organizationId, datasetId);
            var attempted = new ArrayList<Map<String, Object>>();
            int failures = 0;
            for (int offset = 0; offset < cases.size(); offset += agenticRetrievalParallelism) {
                ensureNotCancelled(evaluationRunId);
                var batch = cases.subList(offset, Math.min(
                        cases.size(), offset + agenticRetrievalParallelism));
                var futures = batch.stream().map(evaluationCase -> CompletableFuture.supplyAsync(
                        () -> evaluateRoute(organizationId, request, evaluationCase), executor)).toList();
                for (int index = 0; index < batch.size(); index++) {
                    ensureNotCancelled(evaluationRunId);
                    var evaluationCase = batch.get(index);
                    var execution = futures.get(index).join();
                    attempted.add(execution.metrics());
                    if (execution.error() != null) failures++;
                    repository.saveResult(evaluationRunId, evaluationCase.id(), execution.metrics(), execution.error());
                }
            }
            var aggregate = new LinkedHashMap<>(aggregateRouting(cases.size(), failures, attempted));
            aggregate.put("execution", "ROUTING_ONLY");
            aggregate.put("requestedMode", RunMode.AUTO.name());
            aggregate.put("caseParallelism", agenticRetrievalParallelism);
            aggregate.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
            aggregate.put("documentIds", request.scope().documentIds());
            aggregate.put("metadataFilterCount", request.filters().size());
            if (request.modelProfileId() != null) aggregate.put("modelProfileId", request.modelProfileId());
            repository.completeRun(evaluationRunId, Map.copyOf(aggregate));
        } catch (CancellationException exception) {
            handleCancellation(evaluationRunId, exception);
        } catch (Exception exception) {
            repository.failRun(evaluationRunId, message(exception));
        }
    }

    private void executeResumedRouting(
            UUID organizationId,
            UUID evaluationRunId,
            StartEvaluationRunRequest request,
            List<EvaluationCase> allCases,
            Map<UUID, com.yanyue.rag.domain.evaluation.EvaluationResult> previous,
            UUID previousRunId
    ) {
        var attempted = new ArrayList<Map<String, Object>>();
        var pending = new ArrayList<EvaluationCase>();
        for (var evaluationCase : allCases) {
            var result = previous.get(evaluationCase.id());
            if (reusableRoutingResult(result)) {
                var reused = reusedMetrics(result.metrics(), previousRunId);
                repository.saveResult(evaluationRunId, evaluationCase.id(), reused, null);
                attempted.add(reused);
            } else {
                pending.add(evaluationCase);
            }
        }
        int failures = 0;
        for (int offset = 0; offset < pending.size(); offset += agenticRetrievalParallelism) {
            ensureNotCancelled(evaluationRunId);
            var batch = pending.subList(offset, Math.min(pending.size(), offset + agenticRetrievalParallelism));
            var futures = batch.stream().map(evaluationCase -> CompletableFuture.supplyAsync(
                    () -> evaluateRoute(organizationId, request, evaluationCase), executor)).toList();
            for (int index = 0; index < batch.size(); index++) {
                ensureNotCancelled(evaluationRunId);
                var evaluationCase = batch.get(index);
                var execution = futures.get(index).join();
                attempted.add(execution.metrics());
                if (execution.error() != null) failures++;
                repository.saveResult(evaluationRunId, evaluationCase.id(), execution.metrics(), execution.error());
            }
        }
        var aggregate = new LinkedHashMap<>(aggregateRouting(allCases.size(), failures, attempted));
        aggregate.put("execution", "ROUTING_ONLY");
        aggregate.put("requestedMode", RunMode.AUTO.name());
        aggregate.put("caseParallelism", agenticRetrievalParallelism);
        aggregate.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
        aggregate.put("documentIds", request.scope().documentIds());
        aggregate.put("metadataFilterCount", request.filters().size());
        aggregate.put("reusedSuccessfulCases", allCases.size() - pending.size());
        aggregate.put("retriedCases", pending.size());
        aggregate.put("newlySuccessfulCases", pending.size() - failures);
        aggregate.put("remainingFailedCases", failures);
        aggregate.put("resumedFromRunId", previousRunId);
        aggregate.put("attemptNumber", attempts == null ? 1 : attempts.loadLineage(evaluationRunId)
                .map(com.yanyue.rag.domain.evaluation.EvaluationRunLineage::attemptNumber).orElse(1));
        if (request.modelProfileId() != null) aggregate.put("modelProfileId", request.modelProfileId());
        repository.completeRun(evaluationRunId, Map.copyOf(aggregate));
    }

    private boolean reusableRoutingResult(
            com.yanyue.rag.domain.evaluation.EvaluationResult result
    ) {
        if (result == null || result.errorMessage() != null || result.metrics().isEmpty()) return false;
        var metrics = result.metrics();
        return isRouteMode(metrics.get("selectedMode"))
                && !"ERROR".equals(String.valueOf(metrics.getOrDefault("routeDecisionSource", "ERROR")));
    }

    private RoutingCaseExecution evaluateRoute(
            UUID organizationId,
            StartEvaluationRunRequest request,
            EvaluationCase evaluationCase
    ) {
        var started = System.nanoTime();
        var metrics = new LinkedHashMap<String, Object>();
        var expectedMode = expectedRouteMode(evaluationCase.metadata());
        metrics.put("execution", "ROUTING_ONLY");
        metrics.put("expectedMode", expectedMode == null ? "UNKNOWN" : expectedMode.name());
        try {
            var selection = runCoordinator.selectMode(organizationId, new CreateRunRequest(
                    evaluationCase.question(), RunMode.AUTO, request.scope(),
                    request.filters(), request.modelProfileId()));
            long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            var source = routeDecisionSource(selection);
            metrics.put("selectedMode", selection.mode().name());
            metrics.put("routeReason", selection.reason());
            metrics.put("routeDecisionSource", source);
            if (selection.routerProfile() != null) metrics.put("routerProfile", selection.routerProfile());
            if (selection.titleHitCount() >= 0) metrics.put("titleHitCount", selection.titleHitCount());
            metrics.put("routeClassifiedByModel", selection.classifiedByModel());
            metrics.put("routerFallback", "FALLBACK".equals(source));
            metrics.put("classifierAttempted", selection.classifiedByModel() || "FALLBACK".equals(source));
            metrics.put("classifierSucceeded", selection.classifiedByModel());
            metrics.put("routingCorrect", expectedMode != null && expectedMode == selection.mode() ? 1.0 : 0.0);
            metrics.put("latencyMs", latencyMs);
            return new RoutingCaseExecution(Map.copyOf(metrics), null);
        } catch (RuntimeException failure) {
            long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            metrics.put("selectedMode", "ERROR");
            metrics.put("routeReason", message(failure));
            metrics.put("routeDecisionSource", "ERROR");
            metrics.put("routeClassifiedByModel", false);
            metrics.put("routerFallback", false);
            metrics.put("classifierAttempted", true);
            metrics.put("classifierSucceeded", false);
            metrics.put("routingCorrect", 0.0);
            metrics.put("latencyMs", latencyMs);
            return new RoutingCaseExecution(Map.copyOf(metrics), message(failure));
        }
    }

    private void executeRag(
            UUID organizationId,
            UUID userId,
            UUID evaluationRunId,
            UUID datasetId,
            StartEvaluationRunRequest request,
            boolean retrievalOnly
    ) {
        try {
            repository.markRunRunning(evaluationRunId);
            ensureNotCancelled(evaluationRunId);
            var cases = repository.findCases(organizationId, datasetId);
            validateConversationCases(cases);
            if (cases.stream().allMatch(value -> conversationSpec(value.metadata()) == null)) {
                executeIndependentRagCases(
                        organizationId, userId, evaluationRunId, request, retrievalOnly, cases);
                return;
            }
            var completed = new ArrayList<CaseMetrics>();
            var conversations = new LinkedHashMap<String, UUID>();
            var failedConversationGroups = new LinkedHashSet<String>();
            int failures = 0;
            int groupedCases = 0;
            int createdConversations = 0;
            for (var evaluationCase : cases) {
                ensureNotCancelled(evaluationRunId);
                UUID ragRunId = null;
                var conversation = conversationSpec(evaluationCase.metadata());
                boolean conversationReused = false;
                if (conversation != null) groupedCases++;
                if (conversation != null && failedConversationGroups.contains(conversation.group())) {
                    failures++;
                    repository.saveResult(
                            evaluationRunId, evaluationCase.id(), null,
                            conversationDiagnostics(conversation, true, true),
                            "Previous turn in conversation group failed");
                    continue;
                }
                try {
                    UUID conversationId;
                    if (conversation == null) {
                        conversationId = repository.createEvaluationConversation(
                                organizationId, userId, evaluationRunId);
                        createdConversations++;
                    } else {
                        conversationId = conversations.get(conversation.group());
                        conversationReused = conversationId != null;
                        if (conversationId == null) {
                            conversationId = repository.createEvaluationConversation(
                                    organizationId, userId, evaluationRunId);
                            conversations.put(conversation.group(), conversationId);
                            createdConversations++;
                        }
                    }
                    var runRequest = new CreateRunRequest(
                            evaluationCase.question(), request.mode(), request.scope(),
                            request.filters(), request.modelProfileId());
                    var accepted = retrievalOnly
                            ? runCoordinator.startAgenticRetrieval(
                                    organizationId, userId, conversationId, runRequest)
                            : runCoordinator.start(organizationId, userId, conversationId, runRequest);
                    ragRunId = accepted.runId();
                    trackRagRun(evaluationRunId, ragRunId);
                    var outcome = awaitOutcome(organizationId, evaluationRunId, ragRunId);
                    if (!"COMPLETED".equals(outcome.status())) {
                        throw new IllegalStateException(outcome.errorMessage() == null
                                ? "RAG evaluation run ended with " + outcome.status()
                                : outcome.errorMessage());
                    }
                    var candidates = repository.findRagRunCandidates(organizationId, ragRunId);
                    long latencyMs = outcome.startedAt() == null || outcome.completedAt() == null ? 0
                            : Math.max(0, java.time.Duration.between(
                                    outcome.startedAt(), outcome.completedAt()).toMillis());
                    var metrics = grade(evaluationCase, candidates, latencyMs, retrievalOnly ? null : outcome,
                            repository.findRagRunRetrievalDiagnostics(organizationId, ragRunId),
                            repository.findRagRunAcceptedEvidenceTexts(organizationId, ragRunId));
                    if (retrievalOnly) {
                        var diagnostics = new LinkedHashMap<String, Object>();
                        diagnostics.put("execution", "AGENTIC_RETRIEVAL_ONLY");
                        diagnostics.put("ragRunId", ragRunId);
                        diagnostics.put("selectedMode", outcome.selectedMode() == null
                                ? RunMode.DEEP.name() : outcome.selectedMode());
                        diagnostics.put("answerGenerationSkipped", true);
                        if (outcome.runtimeSnapshot() != null) {
                            diagnostics.put("runtimeSnapshot", outcome.runtimeSnapshot());
                        }
                        metrics = withValues(metrics, diagnostics);
                    } else {
                        metrics = judgeCase(
                                organizationId, request, evaluationCase, ragRunId, outcome, metrics);
                    }
                    metrics = withConversationDiagnostics(metrics, conversation, conversationReused);
                    repository.saveResult(evaluationRunId, evaluationCase.id(), ragRunId, metrics.values(), null);
                    completed.add(metrics);
                } catch (Exception exception) {
                    failures++;
                    if (conversation != null) failedConversationGroups.add(conversation.group());
                    repository.saveResult(
                            evaluationRunId, evaluationCase.id(), ragRunId,
                            conversationDiagnostics(conversation, conversationReused, false),
                            message(exception));
                }
            }
            var aggregate = new LinkedHashMap<>(aggregate(cases.size(), failures, completed));
            aggregate.put("execution", retrievalOnly ? "AGENTIC_RETRIEVAL_ONLY" : "RAG");
            aggregate.put("requestedMode", request.mode().name());
            aggregate.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
            aggregate.put("documentIds", request.scope().documentIds());
            aggregate.put("metadataFilterCount", request.filters().size());
            aggregate.put("judgeMode", request.judgeMode().name());
            aggregate.put("answerGenerationSkipped", retrievalOnly);
            aggregate.put("conversationGroupCount", conversations.size());
            aggregate.put("groupedCaseCount", groupedCases);
            aggregate.put("evaluationConversationCount", createdConversations);
            if (request.modelProfileId() != null) {
                aggregate.put("modelProfileId", request.modelProfileId());
            }
            repository.completeRun(evaluationRunId, Map.copyOf(aggregate));
        } catch (CancellationException exception) {
            handleCancellation(evaluationRunId, exception);
        } catch (Exception exception) {
            repository.failRun(evaluationRunId, message(exception));
        }
    }

    private void executeIndependentRagCases(
            UUID organizationId,
            UUID userId,
            UUID evaluationRunId,
            StartEvaluationRunRequest request,
            boolean retrievalOnly,
            List<EvaluationCase> cases
    ) {
        executeIndependentRagCases(organizationId, userId, evaluationRunId, request,
                retrievalOnly, cases.size(), cases, new ArrayList<>(), 0);
    }

    private void executeIndependentRagCases(
            UUID organizationId,
            UUID userId,
            UUID evaluationRunId,
            StartEvaluationRunRequest request,
            boolean retrievalOnly,
            int totalCaseCount,
            List<EvaluationCase> cases,
            List<CaseMetrics> completed,
            int reusedSuccessfulCases
    ) {
        int failures = 0;
        for (int offset = 0; offset < cases.size(); offset += agenticRetrievalParallelism) {
            ensureNotCancelled(evaluationRunId);
            var batch = cases.subList(offset, Math.min(
                    cases.size(), offset + agenticRetrievalParallelism));
            var futures = batch.stream().map(evaluationCase -> CompletableFuture.supplyAsync(
                    () -> executeIndependentRagCaseWithRetry(
                            organizationId, userId, evaluationRunId, request, retrievalOnly,
                            evaluationCase),
                    executor)).toList();
            for (int index = 0; index < batch.size(); index++) {
                ensureNotCancelled(evaluationRunId);
                var evaluationCase = batch.get(index);
                var execution = futures.get(index).join();
                if (execution.error() == null) {
                    repository.saveResult(
                            evaluationRunId, evaluationCase.id(), execution.ragRunId(),
                            execution.metrics().values(), null);
                    completed.add(execution.metrics());
                } else {
                    if (authenticationFailure(execution.error())) {
                        throw new IllegalStateException(
                                "Evaluation stopped after provider authentication/authorization failure: "
                                        + execution.error());
                    }
                    failures++;
                    repository.saveResult(
                            evaluationRunId, evaluationCase.id(), execution.ragRunId(),
                            execution.failureMetrics(), execution.error());
                }
            }
        }
        var aggregate = new LinkedHashMap<>(aggregate(totalCaseCount, failures, completed));
        aggregate.put("execution", retrievalOnly ? "AGENTIC_RETRIEVAL_ONLY" : "RAG");
        aggregate.put("requestedMode", retrievalOnly ? RunMode.DEEP.name() : request.mode().name());
        aggregate.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
        aggregate.put("documentIds", request.scope().documentIds());
        aggregate.put("metadataFilterCount", request.filters().size());
        aggregate.put("judgeMode", retrievalOnly ? EvaluationJudgeMode.NONE.name() : request.judgeMode().name());
        aggregate.put("answerGenerationSkipped", retrievalOnly);
        aggregate.put("caseParallelism", agenticRetrievalParallelism);
        aggregate.put("conversationGroupCount", 0);
        aggregate.put("groupedCaseCount", 0);
        aggregate.put("evaluationConversationCount", totalCaseCount);
        aggregate.put("reusedSuccessfulCases", reusedSuccessfulCases);
        aggregate.put("retriedCases", cases.size());
        aggregate.put("newlySuccessfulCases", cases.size() - failures);
        aggregate.put("remainingFailedCases", failures);
        if (attempts != null) {
            attempts.loadLineage(evaluationRunId).ifPresent(lineage -> {
                aggregate.put("attemptNumber", lineage.attemptNumber());
                if (lineage.resumedFromRunId() != null) {
                    aggregate.put("resumedFromRunId", lineage.resumedFromRunId());
                }
            });
        }
        if (request.modelProfileId() != null) {
            aggregate.put("modelProfileId", request.modelProfileId());
        }
        repository.completeRun(evaluationRunId, Map.copyOf(aggregate));
    }

    private AgenticCaseExecution executeIndependentRagCaseWithRetry(
            UUID organizationId,
            UUID userId,
            UUID evaluationRunId,
            StartEvaluationRunRequest request,
            boolean retrievalOnly,
            EvaluationCase evaluationCase
    ) {
        AgenticCaseExecution last = null;
        for (int attemptNumber = 1; attemptNumber <= 3; attemptNumber++) {
            ensureNotCancelled(evaluationRunId);
            last = executeIndependentRagCase(
                    organizationId, userId, evaluationRunId, request, retrievalOnly,
                    evaluationCase, attemptNumber);
            if (last.error() == null || !transientFailure(last.error()) || attemptNumber == 3) {
                if (last.error() == null || attemptNumber == 1) return last;
                var diagnostics = new LinkedHashMap<>(last.failureMetrics());
                diagnostics.put("automaticAttempts", attemptNumber);
                diagnostics.put("transientRetries", attemptNumber - 1);
                return new AgenticCaseExecution(last.ragRunId(), last.metrics(), Map.copyOf(diagnostics), last.error());
            }
        }
        return last;
    }

    private AgenticCaseExecution executeIndependentRagCase(
            UUID organizationId,
            UUID userId,
            UUID evaluationRunId,
            StartEvaluationRunRequest request,
            boolean retrievalOnly,
            EvaluationCase evaluationCase,
            int attemptNumber
    ) {
        UUID ragRunId = null;
        var attemptId = UUID.randomUUID();
        var attemptStarted = clock.instant();
        UUID previousAttemptId = previousAttemptId(evaluationRunId, evaluationCase.id());
        saveCaseAttempt(new EvaluationCaseAttempt(attemptId, evaluationRunId, evaluationCase.id(), null,
                attemptNumber, "RUNNING", previousAttemptId, Map.of(), null,
                attemptStarted, null, attemptStarted));
        try {
            var conversationId = repository.createEvaluationConversation(
                    organizationId, userId, evaluationRunId);
            var runRequest = new CreateRunRequest(
                    evaluationCase.question(), retrievalOnly ? RunMode.DEEP : request.mode(), request.scope(),
                    request.filters(), request.modelProfileId());
            var accepted = retrievalOnly
                    ? runCoordinator.startAgenticRetrieval(organizationId, userId, conversationId, runRequest)
                    : runCoordinator.start(organizationId, userId, conversationId, runRequest);
            ragRunId = accepted.runId();
            trackRagRun(evaluationRunId, ragRunId);
            var outcome = awaitOutcome(organizationId, evaluationRunId, ragRunId);
            if (!"COMPLETED".equals(outcome.status())) {
                throw new IllegalStateException(outcome.errorMessage() == null
                        ? "RAG evaluation run ended with " + outcome.status()
                        : outcome.errorMessage());
            }
            var candidates = repository.findRagRunCandidates(organizationId, ragRunId);
            long latencyMs = outcome.startedAt() == null || outcome.completedAt() == null ? 0
                    : Math.max(0, java.time.Duration.between(
                            outcome.startedAt(), outcome.completedAt()).toMillis());
            var metrics = grade(evaluationCase, candidates, latencyMs, retrievalOnly ? null : outcome,
                    repository.findRagRunRetrievalDiagnostics(organizationId, ragRunId),
                    repository.findRagRunAcceptedEvidenceTexts(organizationId, ragRunId));
            if (retrievalOnly) {
                var diagnostics = new LinkedHashMap<String, Object>();
                diagnostics.put("execution", "AGENTIC_RETRIEVAL_ONLY");
                diagnostics.put("ragRunId", ragRunId);
                diagnostics.put("selectedMode", outcome.selectedMode() == null
                        ? RunMode.DEEP.name() : outcome.selectedMode());
                diagnostics.put("answerGenerationSkipped", true);
                if (outcome.runtimeSnapshot() != null) {
                    diagnostics.put("runtimeSnapshot", outcome.runtimeSnapshot());
                }
                metrics = withValues(metrics, diagnostics);
            } else {
                metrics = judgeCase(
                        organizationId, request, evaluationCase, ragRunId, outcome, metrics);
            }
            var degradation = controlledAgenticDegradation(metrics.values());
            if (degradation != null) {
                throw new IllegalStateException(degradation);
            }
            var completedMetrics = metrics;
            saveCaseAttempt(new EvaluationCaseAttempt(attemptId, evaluationRunId, evaluationCase.id(), ragRunId,
                    attemptNumber, "SUCCEEDED", previousAttemptId, completedMetrics.values(), null,
                    attemptStarted, clock.instant(), attemptStarted));
            return new AgenticCaseExecution(ragRunId, completedMetrics, Map.of(), null);
        } catch (Exception exception) {
            var failure = message(exception);
            var failureMetrics = new LinkedHashMap<String, Object>();
            failureMetrics.put("execution", retrievalOnly ? "AGENTIC_RETRIEVAL_ONLY" : "RAG");
            failureMetrics.put("latencyMs", Math.max(0,
                    Duration.between(attemptStarted, clock.instant()).toMillis()));
            failureMetrics.put("failurePhase", failurePhase(exception));
            failureMetrics.put("failureType", exception.getClass().getSimpleName());
            if (ragRunId != null) {
                failureMetrics.put("ragRunId", ragRunId);
                try {
                    var outcome = repository.findRagRunOutcome(organizationId, ragRunId).orElse(null);
                    if (outcome != null) {
                        if (outcome.selectedMode() != null) failureMetrics.put("selectedMode", outcome.selectedMode());
                        if (outcome.runtimeSnapshot() != null) failureMetrics.put("runtimeSnapshot", outcome.runtimeSnapshot());
                    }
                    failureMetrics.put("toolDiagnostics",
                            repository.findRagRunRetrievalDiagnostics(organizationId, ragRunId));
                } catch (RuntimeException diagnosticFailure) {
                    failureMetrics.put("diagnosticError", limitMessage(diagnosticFailure));
                }
            }
            saveCaseAttempt(new EvaluationCaseAttempt(attemptId, evaluationRunId, evaluationCase.id(), ragRunId,
                    attemptNumber, "FAILED", previousAttemptId, Map.copyOf(failureMetrics), failure,
                    attemptStarted, clock.instant(), attemptStarted));
            return new AgenticCaseExecution(ragRunId, null, Map.copyOf(failureMetrics), failure);
        }
    }

    /**
     * A completed RAG run is not necessarily a healthy evaluation sample. Controlled
     * Agentic RAG records recoverable research failures in diagnostics so the pipeline
     * can finish for interactive callers; evaluation must classify those samples as
     * failed and let resume retry them instead of treating degraded evidence as truth.
     */
    private String controlledAgenticDegradation(Map<String, Object> metrics) {
        if (!RunMode.DEEP.name().equals(String.valueOf(metrics.get("selectedMode")))) return null;
        if (!(metrics.get("runtimeSnapshot") instanceof Map<?, ?> runtime)) return null;
        var pipelineVersion = String.valueOf(runtime.get("pipelineVersion"));
        if (!java.util.Set.of("agentic-rag-v4", "agentic-rag-v5", "agentic-rag-v7", "agentic-rag-v8")
                .contains(pipelineVersion)) return null;
        if (!(metrics.get("toolDiagnostics") instanceof Map<?, ?> diagnostics)) {
            return "Controlled Agentic RAG diagnostics are missing";
        }
        var reasons = new ArrayList<String>();
        if (number(diagnostics.get("failedSupportActionCount")) > 0) {
            reasons.add("failed support action");
        }
        if (failedModelLogicalCalls(diagnostics) > 0) {
            reasons.add("failed model logical call");
        }
        if (number(diagnostics.get("hiddenEvidenceOutcomeCount")) > 0) {
            reasons.add("hidden evidence outcome");
        }
        if (!validEvidenceJudgeTopology(pipelineVersion, diagnostics)) {
            reasons.add("unexpected evidence judge call count");
        }
        return reasons.isEmpty() ? null : "Controlled Agentic RAG chain degraded: " + String.join(", ", reasons);
    }

    private boolean validEvidenceJudgeTopology(String pipelineVersion, Map<?, ?> diagnostics) {
        var judgeCallCount = number(diagnostics.get("judgeCallCount"));
        var primaryGoalCount = number(diagnostics.get("primaryGoalCount"));
        return judgeCallCount == 1
                || ("agentic-rag-v8".equals(pipelineVersion)
                    && primaryGoalCount > 0
                    && judgeCallCount == primaryGoalCount);
    }

    private long failedModelLogicalCalls(Map<?, ?> diagnostics) {
        if (diagnostics.containsKey("modelFailedLogicalCallCount")) {
            return number(diagnostics.get("modelFailedLogicalCallCount"));
        }
        // Metrics persisted before logical-failure telemetry was introduced only
        // expose failed physical attempts. Keep those historical runs conservative.
        return number(diagnostics.get("modelFailedAttemptCount"));
    }

    private UUID previousAttemptId(UUID evaluationRunId, UUID evaluationCaseId) {
        if (attempts == null) return null;
        var current = attempts.loadCaseAttempts(evaluationRunId, evaluationCaseId);
        if (!current.isEmpty()) return current.getLast().id();
        return attempts.loadLineage(evaluationRunId)
                .map(com.yanyue.rag.domain.evaluation.EvaluationRunLineage::resumedFromRunId)
                .filter(java.util.Objects::nonNull)
                .flatMap(previous -> attempts.loadCaseAttempts(previous, evaluationCaseId).stream()
                        .reduce((first, second) -> second))
                .map(EvaluationCaseAttempt::id)
                .orElse(null);
    }

    private void saveCaseAttempt(EvaluationCaseAttempt attempt) {
        if (attempts != null) attempts.saveCaseAttempt(attempt);
    }

    private boolean authenticationFailure(String error) {
        if (error == null) return false;
        return error.matches("(?s).*HTTP\\s+(401|403).*")
                || error.matches("(?s).*(Unauthorized|Forbidden|authentication failed|authorization failed).*");
    }

    private boolean transientFailure(String error) {
        if (error == null || authenticationFailure(error)) return false;
        var normalized = error.toLowerCase(Locale.ROOT);
        return normalized.contains("timed out")
                || normalized.contains("timeout")
                || normalized.contains("rate limit")
                || normalized.contains("too many requests")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("service unavailable")
                || normalized.matches("(?s).*http\\s+(429|5[0-9]{2}).*");
    }

    private EvaluationRepository.RagRunOutcome awaitOutcome(
            UUID organizationId,
            UUID evaluationRunId,
            UUID ragRunId
    ) {
        long deadline = System.nanoTime() + CASE_TIMEOUT.toNanos();
        try {
            while (true) {
                ensureNotCancelled(evaluationRunId);
                var outcome = repository.findRagRunOutcome(organizationId, ragRunId).orElse(null);
                if (outcome != null
                        && java.util.Set.of("COMPLETED", "FAILED", "CANCELLED").contains(outcome.status())) {
                    return outcome;
                }
                if (System.nanoTime() >= deadline) {
                    if (runCoordinator != null) runCoordinator.cancel(ragRunId);
                    throw new IllegalStateException("RAG evaluation case timed out after "
                            + CASE_TIMEOUT.toMinutes() + " minutes");
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Evaluation interrupted");
                }
            }
        } finally {
            untrackRagRun(evaluationRunId, ragRunId);
        }
    }

    private CaseMetrics evaluateCase(RetrievalScope scope, EvaluationCase evaluationCase) {
        var started = System.nanoTime();
        var keyword = CompletableFuture.supplyAsync(
                () -> retrieval.keywordSearch(evaluationCase.question(), scope, 30), executor);
        var semantic = CompletableFuture.supplyAsync(
                () -> retrieval.semanticSearch(evaluationCase.question(), scope, 30, 4), executor);
        var candidates = ReciprocalRankFusion.fuse(List.of(keyword.join(), semantic.join()), 40);
        var latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);

        return grade(evaluationCase, candidates, latencyMs, null);
    }

    private CaseMetrics grade(
            EvaluationCase evaluationCase,
            List<RetrievalHit> candidates,
            long latencyMs,
            EvaluationRepository.RagRunOutcome outcome
    ) {
        return grade(evaluationCase, candidates, latencyMs, outcome, Map.of());
    }

    private CaseMetrics grade(
            EvaluationCase evaluationCase,
            List<RetrievalHit> candidates,
            long latencyMs,
            EvaluationRepository.RagRunOutcome outcome,
            Map<String, Object> diagnostics
    ) {
        return grade(evaluationCase, candidates, latencyMs, outcome, diagnostics, List.of());
    }

    private CaseMetrics grade(
            EvaluationCase evaluationCase,
            List<RetrievalHit> candidates,
            long latencyMs,
            EvaluationRepository.RagRunOutcome outcome,
            Map<String, Object> diagnostics,
            List<String> acceptedEvidenceTexts
    ) {
        var expected = new LinkedHashSet<>(evaluationCase.expectedDocumentIds());
        var rankedDocuments = candidates.stream()
                .collect(java.util.stream.Collectors.toMap(
                        RetrievalHit::documentId,
                        hit -> hit,
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream().toList();
        var topFive = rankedDocuments.stream().limit(5).toList();
        var topTen = rankedDocuments.stream().limit(10).toList();
        var topFiveDocumentIds = topFive.stream().map(RetrievalHit::documentId).toList();
        var retrievedDocuments = topTen.stream().map(RetrievalHit::documentId).toList();
        var forbiddenDocuments = metadataDocumentIds(evaluationCase.metadata(), "forbiddenDocumentIds");
        long forbiddenDocumentHits = forbiddenDocuments.stream().filter(retrievedDocuments::contains).count();
        long matchedAt5 = expected.stream().filter(topFiveDocumentIds::contains).count();
        long matchedAt10 = expected.stream().filter(retrievedDocuments::contains).count();
        double recallAt5 = expected.isEmpty() ? 0 : (double) matchedAt5 / expected.size();
        double recallAt10 = expected.isEmpty() ? 0 : (double) matchedAt10 / expected.size();
        double precisionAt5 = topFive.isEmpty() ? 0 : (double) matchedAt5 / topFive.size();
        double precisionAt10 = topTen.isEmpty() ? 0 : (double) matchedAt10 / topTen.size();
        int firstRelevantRank = 0;
        for (int index = 0; index < topTen.size(); index++) {
            if (expected.contains(topTen.get(index).documentId())) {
                firstRelevantRank = index + 1;
                break;
            }
        }
        double reciprocalRank = firstRelevantRank == 0 ? 0 : 1.0 / firstRelevantRank;
        double reciprocalRankAt5 = firstRelevantRank == 0 || firstRelevantRank > 5 ? 0 : reciprocalRank;
        double ndcgAt5 = ndcg(topFive.stream().map(hit -> expected.contains(hit.documentId())).toList(), Math.min(5, expected.size()));
        double ndcgAt10 = ndcg(topTen.stream().map(hit -> expected.contains(hit.documentId())).toList(), Math.min(10, expected.size()));
        double mapAt5 = averagePrecision(topFive.stream().map(hit -> expected.contains(hit.documentId())).toList(), 5, expected.size());
        double mapAt10 = averagePrecision(topTen.stream().map(hit -> expected.contains(hit.documentId())).toList(), 10, expected.size());
        double retrievalCandidateCoverage = answerCoverage(evaluationCase.expectedAnswer(), candidates);
        var acceptedEvidenceContext = String.join("\n", acceptedEvidenceTexts);
        double acceptedEvidenceCoverage = textCoverage(
                evaluationCase.expectedAnswer(), acceptedEvidenceContext);
        var researchContext = candidates.stream().map(RetrievalHit::text)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!acceptedEvidenceContext.isBlank()) {
            researchContext = researchContext.isBlank()
                    ? acceptedEvidenceContext
                    : researchContext + "\n" + acceptedEvidenceContext;
        }
        double researchContextCoverage = textCoverage(evaluationCase.expectedAnswer(), researchContext);
        double generatedAnswerCoverage = outcome == null
                ? researchContextCoverage
                : textCoverage(evaluationCase.expectedAnswer(), outcome.answer());
        boolean hasNoAnswerExpectation = evaluationCase.metadata().get("expectNoAnswer") instanceof Boolean;
        boolean expectsNoAnswer = Boolean.TRUE.equals(evaluationCase.metadata().get("expectNoAnswer"));
        boolean pipelineNoAnswer = outcome != null
                && outcome.noAnswerReason() != null && !outcome.noAnswerReason().isBlank();
        boolean answerAbstention = outcome != null && !pipelineNoAnswer
                && evidenceLimitedAbstention(outcome.answer());
        boolean returnedNoAnswer = pipelineNoAnswer || answerAbstention;
        double noAnswerCorrect = !hasNoAnswerExpectation ? 0
                : expectsNoAnswer == returnedNoAnswer ? 1 : 0;
        int citationCount = outcome == null ? 0 : outcome.citationCount();
        double citationResolvableRate = citationCount == 0 ? 0
                : (double) outcome.resolvableCitationCount() / citationCount;
        int effectiveLeakCount = outcome == null
                ? Math.toIntExact(number(diagnostics.get("scopeLeakCount")))
                : outcome.effectiveVersionLeakCount();

        var topDocuments = new ArrayList<Map<String, Object>>();
        for (var hit : rankedDocuments) {
            topDocuments.add(Map.of(
                    "rank", topDocuments.size() + 1,
                    "documentId", hit.documentId(),
                    "title", hit.documentTitle(),
                    "score", hit.score()
            ));
            if (topDocuments.size() == 5) break;
        }

        var values = new LinkedHashMap<String, Object>();
        values.put("candidateCount", candidates.size());
        values.put("expectedDocumentCount", expected.size());
        values.put("matchedDocumentCountAt5", matchedAt5);
        values.put("matchedDocumentCount", matchedAt10);
        values.put("recallAt5", recallAt5);
        values.put("recallAt10", recallAt10);
        values.put("precisionAt5", precisionAt5);
        values.put("precisionAt10", precisionAt10);
        values.put("ndcgAt5", ndcgAt5);
        values.put("ndcgAt10", ndcgAt10);
        values.put("mapAt5", mapAt5);
        values.put("mapAt10", mapAt10);
        values.put("reciprocalRankAt5", reciprocalRankAt5);
        values.put("reciprocalRank", reciprocalRank);
        values.put("hitAt5", firstRelevantRank > 0 && firstRelevantRank <= 5 ? 1 : 0);
        values.put("hitAt10", firstRelevantRank > 0 ? 1 : 0);
        values.put("firstRelevantRank", firstRelevantRank);
        if (!forbiddenDocuments.isEmpty()) {
            values.put("forbiddenDocumentCount", forbiddenDocuments.size());
            values.put("forbiddenDocumentHitCount", forbiddenDocumentHits);
            values.put("forbiddenDocumentLeakFree", forbiddenDocumentHits == 0 ? 1.0 : 0.0);
        }
        values.put("expectedAnswerCoverage", generatedAnswerCoverage);
        values.put("retrievalCandidateCoverage", retrievalCandidateCoverage);
        values.put("acceptedEvidenceCoverage", acceptedEvidenceCoverage);
        values.put("researchContextCoverage", researchContextCoverage);
        values.put("evidenceAnswerCoverage", acceptedEvidenceCoverage);
        values.put("latencyMs", latencyMs);
        values.put("effectiveVersionLeakCount", effectiveLeakCount);
        values.put("topDocuments", topDocuments);
        copyGroupingMetadata(values, evaluationCase.metadata());
        values.put("execution", outcome == null ? "RETRIEVAL_ONLY" : "RAG");
        if (!diagnostics.isEmpty()) {
            values.put("toolDiagnostics", diagnostics);
            var allDocumentIds = diagnostics.get("allDocumentIds");
            var deepReadDocumentIds = diagnostics.get("deepReadDocumentIds");
            if (allDocumentIds instanceof List<?> all) {
                long allMatched = expected.stream().filter(all::contains).count();
                values.put("allToolCoverageRecall", expected.isEmpty() ? 0 : (double) allMatched / expected.size());
                var strictTop5 = all.stream().limit(5).toList();
                var strictTop10 = all.stream().limit(10).toList();
                long strictMatched5 = expected.stream().filter(strictTop5::contains).count();
                long strictMatched10 = expected.stream().filter(strictTop10::contains).count();
                values.put("strictDiscoveryRecallAt5", expected.isEmpty() ? 0 : (double) strictMatched5 / expected.size());
                values.put("strictDiscoveryRecallAt10", expected.isEmpty() ? 0 : (double) strictMatched10 / expected.size());
            }
            if (deepReadDocumentIds instanceof List<?> deep) {
                long deepMatched = expected.stream().filter(deep::contains).count();
                values.put("deepReadRecallAtAll", expected.isEmpty() ? 0 : (double) deepMatched / expected.size());
                values.put("deepReadCompliant", deep.isEmpty() ? 0.0 : 1.0);
            }
            long toolCalls = 0;
            long toolFailures = 0;
            long budgetRejections = 0;
            for (var entry : diagnostics.entrySet()) {
                if (!entry.getKey().startsWith("tool.") || !(entry.getValue() instanceof Map<?, ?> tool)) continue;
                toolCalls += number(tool.get("calls"));
                toolFailures += number(tool.get("failed"));
                budgetRejections += number(tool.get("budgetRejected"));
            }
            values.put("toolCallCount", toolCalls);
            values.put("toolFailureCount", toolFailures);
            values.put("budgetRejectionCount", budgetRejections);
            values.put("iterationCount", number(diagnostics.get("iterationCount")));
            values.put("contextCompressionCount", number(diagnostics.get("contextCompressionCount")));
            values.put("inputTokens", number(diagnostics.get("inputTokens")));
            values.put("outputTokens", number(diagnostics.get("outputTokens")));
            values.put("totalTokens", number(diagnostics.get("totalTokens")));
            values.put("scopeLeakCount", number(diagnostics.get("scopeLeakCount")));
            for (var key : List.of(
                    "routeReason", "routeDecisionSource", "routeClassifiedByModel", "routerFallback", "routeLatencyMs",
                    "retrievalTaskCount", "rerankSkippedCount", "evidenceCount", "judgeCallCount",
                    "judgeSufficientCount", "gapQueryCount")) {
                if (diagnostics.containsKey(key)) values.put(key, diagnostics.get(key));
            }
        }
        if (outcome != null) {
            var selectedMode = outcome.selectedMode() == null ? "UNKNOWN" : outcome.selectedMode();
            values.put("ragRunId", outcome.runId());
            values.put("selectedMode", selectedMode);
            var expectedMode = expectedRouteMode(evaluationCase.metadata());
            if (expectedMode != null) {
                values.put("expectedMode", expectedMode.name());
                values.put("routingCorrect", expectedMode.name().equals(selectedMode) ? 1.0 : 0.0);
            }
            values.put("answer", outcome.answer() == null ? "" : outcome.answer());
            values.put("noAnswer", returnedNoAnswer);
            values.put("noAnswerSource", pipelineNoAnswer ? "PIPELINE"
                    : answerAbstention ? "EVIDENCE_LIMITED_ANSWER" : "NONE");
            values.put("noAnswerReason", outcome.noAnswerReason() == null ? "" : outcome.noAnswerReason());
            values.put("citationCount", citationCount);
            values.put("citationResolvableRate", citationResolvableRate);
            values.put("runtimeSnapshot", outcome.runtimeSnapshot());
        }
        if (hasNoAnswerExpectation) values.put("noAnswerCorrect", noAnswerCorrect);
        return new CaseMetrics(Map.copyOf(values), recallAt5, recallAt10,
                reciprocalRankAt5, reciprocalRank,
                firstRelevantRank > 0 && firstRelevantRank <= 5 ? 1 : 0,
                firstRelevantRank > 0 ? 1 : 0, generatedAnswerCoverage, latencyMs,
                !expected.isEmpty(),
                evaluationCase.expectedAnswer() != null && !evaluationCase.expectedAnswer().isBlank(),
                outcome != null, citationCount, citationResolvableRate, effectiveLeakCount,
                hasNoAnswerExpectation, noAnswerCorrect);
    }

    private void copyGroupingMetadata(Map<String, Object> values, Map<String, Object> metadata) {
        for (var key : List.of("challengeType", "sourceProject", "intentCount", "recommendedMode")) {
            var value = metadata.get(key);
            if (value != null && !(value instanceof String string && string.isBlank())) values.put(key, value);
        }
    }

    private RunMode expectedRouteMode(Map<String, Object> metadata) {
        var value = metadata.get("recommendedMode");
        if (!(value instanceof String text) || text.isBlank()) return null;
        try {
            var mode = RunMode.valueOf(text.strip().toUpperCase(Locale.ROOT));
            return mode == RunMode.AUTO ? null : mode;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String routeDecisionSource(RunCoordinator.Selection selection) {
        return selection.decisionSource();
    }

    private double ndcg(List<Boolean> relevant, int idealRelevant) {
        if (relevant.isEmpty() || idealRelevant <= 0) return 0;
        double dcg = 0;
        for (int index = 0; index < relevant.size(); index++) {
            if (relevant.get(index)) dcg += 1.0 / (Math.log(index + 2) / Math.log(2));
        }
        double idcg = 0;
        for (int index = 0; index < Math.min(relevant.size(), idealRelevant); index++) {
            idcg += 1.0 / (Math.log(index + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private double averagePrecision(List<Boolean> relevant, int cutoff, int expectedCount) {
        if (relevant.isEmpty() || expectedCount <= 0) return 0;
        double total = 0;
        int found = 0;
        for (int index = 0; index < Math.min(cutoff, relevant.size()); index++) {
            if (relevant.get(index)) {
                found++;
                total += (double) found / (index + 1);
            }
        }
        return total / Math.min(expectedCount, cutoff);
    }

    private Map<String, Object> aggregate(int caseCount, int failures, List<CaseMetrics> completed) {
        var latencies = completed.stream().map(CaseMetrics::latencyMs).sorted().toList();
        var retrievalCases = completed.stream().filter(CaseMetrics::hasExpectedDocuments).toList();
        var answerCases = completed.stream().filter(CaseMetrics::hasExpectedAnswer).toList();
        var values = new LinkedHashMap<String, Object>();
        values.put("caseCount", caseCount);
        values.put("successfulCases", completed.size());
        values.put("failedCases", failures);
        values.put("retrievalGradedCases", retrievalCases.size());
        values.put("answerGradedCases", answerCases.size());
        values.put("recallAt5", average(retrievalCases.stream().map(CaseMetrics::recallAt5).toList()));
        values.put("recallAt10", average(retrievalCases.stream().map(CaseMetrics::recallAt10).toList()));
        values.put("precisionAt5", averageMetric(completed, "precisionAt5"));
        values.put("precisionAt10", averageMetric(completed, "precisionAt10"));
        values.put("ndcgAt5", averageMetric(completed, "ndcgAt5"));
        values.put("ndcgAt10", averageMetric(completed, "ndcgAt10"));
        values.put("mapAt5", averageMetric(completed, "mapAt5"));
        values.put("mapAt10", averageMetric(completed, "mapAt10"));
        values.put("mrrAt5", average(
                retrievalCases.stream().map(CaseMetrics::reciprocalRankAt5).toList()));
        values.put("mrr", average(retrievalCases.stream().map(CaseMetrics::reciprocalRank).toList()));
        values.put("hitAt5", average(
                retrievalCases.stream().map(metric -> (double) metric.hitAt5()).toList()));
        values.put("hitAt10", average(
                retrievalCases.stream().map(metric -> (double) metric.hitAt10()).toList()));
        values.put("expectedAnswerCoverage", average(
                answerCases.stream().map(CaseMetrics::answerCoverage).toList()));
        values.put("retrievalCandidateCoverage", averageMetric(answerCases, "retrievalCandidateCoverage"));
        values.put("acceptedEvidenceCoverage", averageMetric(answerCases, "acceptedEvidenceCoverage"));
        values.put("researchContextCoverage", averageMetric(answerCases, "researchContextCoverage"));
        values.put("evidenceAnswerCoverage", averageMetric(answerCases, "evidenceAnswerCoverage"));
        values.put("averageLatencyMs", average(
                latencies.stream().map(Long::doubleValue).toList()));
        values.put("p50LatencyMs", percentile(latencies, 0.50));
        values.put("p95LatencyMs", percentile95(latencies));
        values.put("p99LatencyMs", percentile(latencies, 0.99));
        var ragCases = completed.stream().filter(CaseMetrics::ragExecuted).toList();
        var citationCases = ragCases.stream().filter(metric -> metric.citationCount() > 0).toList();
        var noAnswerCases = ragCases.stream().filter(CaseMetrics::hasNoAnswerExpectation).toList();
        values.put("ragExecutedCases", ragCases.size());
        values.put("citationGradedCases", citationCases.size());
        values.put("citationResolvableRate", average(
                citationCases.stream().map(CaseMetrics::citationResolvableRate).toList()));
        values.put("effectiveVersionLeakCount", ragCases.stream()
                .mapToInt(CaseMetrics::effectiveLeakCount).sum());
        values.put("noAnswerGradedCases", noAnswerCases.size());
        values.put("noAnswerAccuracy", average(
                noAnswerCases.stream().map(CaseMetrics::noAnswerCorrect).toList()));
        var semanticScores = metricValues(completed, "semanticAnswerScore");
        var citationEntailmentScores = metricValues(completed, "citationEntailmentScore");
        values.put("semanticAnswerJudgedCases", semanticScores.size());
        values.put("semanticAnswerScore", average(semanticScores));
        values.put("citationEntailmentJudgedCases", citationEntailmentScores.size());
        values.put("citationEntailmentScore", average(citationEntailmentScores));
        values.put("judgeFailedCases", completed.stream()
                .filter(metric -> "FAILED".equals(metric.values().get("judgeStatus"))).count());
        var forbiddenLeakFree = metricValues(completed, "forbiddenDocumentLeakFree");
        var forbiddenHits = metricValues(completed, "forbiddenDocumentHitCount");
        values.put("forbiddenDocumentGradedCases", forbiddenLeakFree.size());
        values.put("forbiddenDocumentLeakFreeRate", average(forbiddenLeakFree));
        values.put("forbiddenDocumentHitCount", forbiddenHits.stream().mapToDouble(Double::doubleValue).sum());
        values.put("toolCallCount", completed.stream().mapToLong(metric -> number(metric.values().get("toolCallCount"))).sum());
        values.put("toolFailureCount", completed.stream().mapToLong(metric -> number(metric.values().get("toolFailureCount"))).sum());
        long totalToolCalls = number(values.get("toolCallCount"));
        long totalToolFailures = number(values.get("toolFailureCount"));
        values.put("toolFailureRate", totalToolCalls == 0 ? 0 : (double) totalToolFailures / totalToolCalls);
        values.put("averageToolCalls", completed.isEmpty() ? 0 : (double) totalToolCalls / completed.size());
        values.put("allToolCoverageRecall", averageMetric(completed, "allToolCoverageRecall"));
        values.put("deepReadRecall", averageMetric(completed, "deepReadRecallAtAll"));
        values.put("strictDiscoveryRecallAt5", averageMetric(completed, "strictDiscoveryRecallAt5"));
        values.put("strictDiscoveryRecallAt10", averageMetric(completed, "strictDiscoveryRecallAt10"));
        values.put("deepReadComplianceRate", averageMetric(completed, "deepReadCompliant"));
        values.put("averageIterations", averageMetric(completed, "iterationCount"));
        values.put("budgetRejectionCount", completed.stream()
                .mapToLong(metric -> number(metric.values().get("budgetRejectionCount"))).sum());
        values.put("contextCompressionCount", completed.stream()
                .mapToLong(metric -> number(metric.values().get("contextCompressionCount"))).sum());
        values.put("inputTokens", completed.stream().mapToLong(metric -> number(metric.values().get("inputTokens"))).sum());
        values.put("outputTokens", completed.stream().mapToLong(metric -> number(metric.values().get("outputTokens"))).sum());
        values.put("totalTokens", completed.stream().mapToLong(metric -> number(metric.values().get("totalTokens"))).sum());
        values.put("scopeLeakCount", completed.stream().mapToLong(metric -> number(metric.values().get("scopeLeakCount"))).sum());
        values.put("byChallengeType", groupedMetrics(completed, "challengeType"));
        values.put("bySourceProject", groupedMetrics(completed, "sourceProject"));
        values.put("byIntentCount", groupedMetrics(completed, "intentCount"));
        values.putAll(routingMetrics(completed.stream().map(CaseMetrics::values).toList()));
        return Map.copyOf(values);
    }

    private Map<String, Object> aggregateRouting(
            int caseCount,
            int failures,
            List<Map<String, Object>> attempted
    ) {
        var latencies = attempted.stream().map(values -> number(values.get("latencyMs"))).sorted().toList();
        var values = new LinkedHashMap<String, Object>();
        values.put("caseCount", caseCount);
        values.put("successfulCases", caseCount - failures);
        values.put("failedCases", failures);
        values.put("averageLatencyMs", average(latencies.stream().map(Long::doubleValue).toList()));
        values.put("p50LatencyMs", percentile(latencies, 0.50));
        values.put("p95LatencyMs", percentile95(latencies));
        values.put("p99LatencyMs", percentile(latencies, 0.99));
        values.putAll(routingMetrics(attempted));
        return Map.copyOf(values);
    }

    private Map<String, Object> routingMetrics(List<Map<String, Object>> rows) {
        var graded = rows.stream().filter(values -> isRouteMode(values.get("expectedMode"))).toList();
        long correct = graded.stream().filter(values -> decimal(values.get("routingCorrect")) >= 1).count();
        long expectedFast = routeCount(graded, "expectedMode", RunMode.FAST.name());
        long expectedDeep = routeCount(graded, "expectedMode", RunMode.DEEP.name());
        long selectedFast = routeCount(graded, "selectedMode", RunMode.FAST.name());
        long selectedDeep = routeCount(graded, "selectedMode", RunMode.DEEP.name());
        long fastCorrect = graded.stream().filter(values -> RunMode.FAST.name().equals(values.get("expectedMode"))
                && RunMode.FAST.name().equals(values.get("selectedMode"))).count();
        long deepCorrect = graded.stream().filter(values -> RunMode.DEEP.name().equals(values.get("expectedMode"))
                && RunMode.DEEP.name().equals(values.get("selectedMode"))).count();
        var confusion = new LinkedHashMap<String, Object>();
        for (var expected : List.of(RunMode.FAST.name(), RunMode.DEEP.name())) {
            var selected = new LinkedHashMap<String, Object>();
            for (var actual : List.of(RunMode.FAST.name(), RunMode.DEEP.name(), "ERROR", "UNKNOWN")) {
                long count = graded.stream().filter(values -> expected.equals(values.get("expectedMode"))
                        && actual.equals(values.get("selectedMode"))).count();
                if (count > 0) selected.put(actual, count);
            }
            confusion.put(expected, Map.copyOf(selected));
        }
        var sources = new LinkedHashMap<String, Object>();
        rows.stream().map(values -> String.valueOf(values.getOrDefault("routeDecisionSource", "UNKNOWN")))
                .distinct().sorted().forEach(source -> sources.put(source,
                        rows.stream().filter(values -> source.equals(String.valueOf(
                                values.getOrDefault("routeDecisionSource", "UNKNOWN")))).count()));
        long classifierAttempts = rows.stream()
                .filter(values -> Boolean.TRUE.equals(values.get("classifierAttempted"))
                        || Boolean.TRUE.equals(values.get("routeClassifiedByModel"))
                        || Boolean.TRUE.equals(values.get("routerFallback"))).count();
        long classifierSuccesses = rows.stream()
                .filter(values -> Boolean.TRUE.equals(values.get("classifierSucceeded"))
                        || Boolean.TRUE.equals(values.get("routeClassifiedByModel"))).count();
        long fallbacks = rows.stream().filter(values -> Boolean.TRUE.equals(values.get("routerFallback"))).count();
        var result = new LinkedHashMap<String, Object>();
        result.put("routingGradedCases", graded.size());
        result.put("routingCorrectCases", correct);
        result.put("routingAccuracy", graded.isEmpty() ? 0 : (double) correct / graded.size());
        result.put("fastRouteRecall", expectedFast == 0 ? 0 : (double) fastCorrect / expectedFast);
        result.put("deepRouteRecall", expectedDeep == 0 ? 0 : (double) deepCorrect / expectedDeep);
        result.put("fastRoutePrecision", selectedFast == 0 ? 0 : (double) fastCorrect / selectedFast);
        result.put("deepRoutePrecision", selectedDeep == 0 ? 0 : (double) deepCorrect / selectedDeep);
        result.put("routingConfusionMatrix", Map.copyOf(confusion));
        result.put("routeDecisionSources", Map.copyOf(sources));
        result.put("classifierAttemptCount", classifierAttempts);
        result.put("classifierSuccessRate", classifierAttempts == 0
                ? 0 : (double) classifierSuccesses / classifierAttempts);
        result.put("routerFallbackCount", fallbacks);
        result.put("routerFallbackRate", rows.isEmpty() ? 0 : (double) fallbacks / rows.size());
        for (var source : List.of("LLM", "HEURISTIC", "FALLBACK")) {
            var sourceRows = graded.stream().filter(values -> source.equals(
                    String.valueOf(values.getOrDefault("routeDecisionSource", "UNKNOWN")))).toList();
            long sourceCorrect = sourceRows.stream()
                    .filter(values -> decimal(values.get("routingCorrect")) >= 1).count();
            result.put(source.toLowerCase(Locale.ROOT) + "RoutingCases", sourceRows.size());
            result.put(source.toLowerCase(Locale.ROOT) + "RoutingAccuracy", sourceRows.isEmpty()
                    ? 0 : (double) sourceCorrect / sourceRows.size());
        }
        return Map.copyOf(result);
    }

    private long routeCount(List<Map<String, Object>> rows, String key, String mode) {
        return rows.stream().filter(values -> mode.equals(values.get(key))).count();
    }

    private boolean isRouteMode(Object value) {
        return RunMode.FAST.name().equals(value) || RunMode.DEEP.name().equals(value);
    }

    private Map<String, Object> groupedMetrics(List<CaseMetrics> metrics, String key) {
        var groups = new LinkedHashMap<String, List<CaseMetrics>>();
        for (var metric : metrics) {
            var value = metric.values().get(key);
            if (value != null) groups.computeIfAbsent(String.valueOf(value), ignored -> new ArrayList<>()).add(metric);
        }
        var result = new LinkedHashMap<String, Object>();
        groups.forEach((name, values) -> result.put(name, Map.of(
                "caseCount", values.size(),
                "recallAt5", average(values.stream().map(CaseMetrics::recallAt5).toList()),
                "recallAt10", average(values.stream().map(CaseMetrics::recallAt10).toList()),
                "mrrAt5", average(values.stream().map(CaseMetrics::reciprocalRankAt5).toList()),
                "hitAt5", average(values.stream().map(metric -> (double) metric.hitAt5()).toList()),
                "hitAt10", average(values.stream().map(metric -> (double) metric.hitAt10()).toList())
        )));
        return Map.copyOf(result);
    }

    private CaseMetrics judgeCase(
            UUID organizationId,
            StartEvaluationRunRequest request,
            EvaluationCase evaluationCase,
            UUID ragRunId,
            EvaluationRepository.RagRunOutcome outcome,
            CaseMetrics metrics
    ) {
        if (request.judgeMode() == EvaluationJudgeMode.NONE) return metrics;
        var additions = new LinkedHashMap<String, Object>();
        if (judge == null) {
            additions.put("judgeStatus", "FAILED");
            additions.put("judgeMode", request.judgeMode().name());
            additions.put("judgeError", "Evaluation judge is unavailable");
        } else {
            try {
                additions.putAll(judge.judge(
                        organizationId, request.modelProfileId(), request.judgeMode(), evaluationCase,
                        outcome.answer(), repository.findRagRunCitations(organizationId, ragRunId)));
            } catch (RuntimeException failure) {
                additions.put("judgeStatus", "FAILED");
                additions.put("judgeMode", request.judgeMode().name());
                additions.put("judgeError", limitMessage(failure));
            }
        }
        return withValues(metrics, additions);
    }

    private CaseMetrics withConversationDiagnostics(
            CaseMetrics metrics,
            ConversationSpec conversation,
            boolean reused
    ) {
        return withValues(metrics, conversationDiagnostics(conversation, reused, false));
    }

    private Map<String, Object> conversationDiagnostics(
            ConversationSpec conversation,
            boolean reused,
            boolean skipped
    ) {
        if (conversation == null) return Map.of();
        return Map.of(
                CONVERSATION_GROUP, conversation.group(),
                CONVERSATION_TURN, conversation.turn(),
                "conversationReused", reused,
                "conversationSkipped", skipped
        );
    }

    private CaseMetrics withValues(CaseMetrics metrics, Map<String, Object> additions) {
        if (additions.isEmpty()) return metrics;
        var merged = new LinkedHashMap<>(metrics.values());
        merged.putAll(additions);
        return new CaseMetrics(
                Map.copyOf(merged), metrics.recallAt5(), metrics.recallAt10(),
                metrics.reciprocalRankAt5(), metrics.reciprocalRank(), metrics.hitAt5(), metrics.hitAt10(),
                metrics.answerCoverage(), metrics.latencyMs(), metrics.hasExpectedDocuments(),
                metrics.hasExpectedAnswer(), metrics.ragExecuted(), metrics.citationCount(),
                metrics.citationResolvableRate(), metrics.effectiveLeakCount(),
                metrics.hasNoAnswerExpectation(), metrics.noAnswerCorrect());
    }

    private CaseMetrics storedMetrics(EvaluationCase evaluationCase, Map<String, Object> values) {
        boolean hasNoAnswerExpectation = evaluationCase.metadata().get("expectNoAnswer") instanceof Boolean;
        return new CaseMetrics(
                Map.copyOf(values), decimal(values.get("recallAt5")), decimal(values.get("recallAt10")),
                decimal(values.get("reciprocalRankAt5")), decimal(values.get("reciprocalRank")),
                (int) number(values.get("hitAt5")), (int) number(values.get("hitAt10")),
                decimal(values.get("expectedAnswerCoverage")), number(values.get("latencyMs")),
                !evaluationCase.expectedDocumentIds().isEmpty(),
                evaluationCase.expectedAnswer() != null && !evaluationCase.expectedAnswer().isBlank(),
                !Boolean.TRUE.equals(values.get("answerGenerationSkipped")),
                (int) number(values.get("citationCount")), decimal(values.get("citationResolvableRate")),
                (int) number(values.get("effectiveVersionLeakCount")), hasNoAnswerExpectation,
                decimal(values.get("noAnswerCorrect")));
    }

    private double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private List<Double> metricValues(List<CaseMetrics> metrics, String key) {
        return metrics.stream().map(CaseMetrics::values).map(values -> values.get(key))
                .filter(Number.class::isInstance).map(Number.class::cast).map(Number::doubleValue).toList();
    }

    private double averageMetric(List<CaseMetrics> metrics, String key) {
        return average(metricValues(metrics, key));
    }

    private String limitMessage(RuntimeException failure) {
        var value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private String failurePhase(Exception failure) {
        var text = message(failure).toLowerCase(Locale.ROOT);
        if (text.contains("timed out") || text.contains("timeout")) return "TIMEOUT";
        if (text.contains("authentication") || text.contains("unauthorized") || text.contains("forbidden")) {
            return "PROVIDER_AUTH";
        }
        if (text.contains("routing") || text.contains("router")) return "ROUTING";
        if (text.contains("judge") || text.contains("coverage")) return "EVIDENCE_JUDGE";
        if (text.contains("deep") || text.contains("evidence")) return "DEEP_READ";
        if (text.contains("retriev") || text.contains("search")) return "RETRIEVAL";
        return "RAG_RUN";
    }

    private double answerCoverage(String expectedAnswer, List<RetrievalHit> candidates) {
        if (expectedAnswer == null || expectedAnswer.isBlank()) return 0;
        var expected = grams(expectedAnswer);
        if (expected.isEmpty()) return 0;
        var evidence = grams(candidates.stream().map(RetrievalHit::text)
                .collect(java.util.stream.Collectors.joining("\n")));
        long covered = expected.stream().filter(evidence::contains).count();
        return (double) covered / expected.size();
    }

    private double textCoverage(String expectedAnswer, String actualAnswer) {
        if (expectedAnswer == null || expectedAnswer.isBlank()) return 0;
        var expected = grams(expectedAnswer);
        if (expected.isEmpty()) return 0;
        var actual = grams(actualAnswer == null ? "" : actualAnswer);
        long covered = expected.stream().filter(actual::contains).count();
        return (double) covered / expected.size();
    }

    private boolean evidenceLimitedAbstention(String answer) {
        if (answer == null || answer.isBlank()) return false;
        var normalized = answer.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return List.of(
                "证据不足", "资料不足", "现有资料未提供", "知识库没有提供", "知识库未提供",
                "无法判断", "无法确定", "无法确认", "无法根据", "无法给出可靠答案", "不能确定",
                "insufficient evidence", "not provided in the knowledge", "cannot determine",
                "unable to determine", "cannot confirm"
        ).stream().anyMatch(normalized::contains);
    }

    private LinkedHashSet<String> grams(String value) {
        var normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
        var codePoints = normalized.codePoints().toArray();
        var grams = new LinkedHashSet<String>();
        if (codePoints.length == 1) {
            grams.add(new String(codePoints, 0, 1));
            return grams;
        }
        for (int index = 0; index + 1 < codePoints.length; index++) {
            grams.add(new String(codePoints, index, 2));
        }
        return grams;
    }

    private double average(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private long percentile95(List<Long> values) {
        return percentile(values, 0.95);
    }

    private long percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(values.size() * fraction) - 1);
        return values.get(Math.min(values.size() - 1, index));
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private EvaluationDataset requireDataset(UUID organizationId, UUID datasetId) {
        return repository.findDataset(organizationId, datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation dataset not found"));
    }

    private void requireCases(UUID organizationId, UUID datasetId) {
        var cases = repository.findCases(organizationId, datasetId);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Evaluation dataset has no cases");
        }
        validateConversationCases(cases);
    }

    private EvaluationRun createQueuedRagRun(
            UUID organizationId,
            UUID datasetId,
            StartEvaluationRunRequest request
    ) {
        var initial = new LinkedHashMap<String, Object>();
        initial.put("execution", "RAG");
        initial.put("requestedMode", request.mode().name());
        initial.put("judgeMode", request.judgeMode().name());
        initial.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
        initial.put("documentIds", request.scope().documentIds());
        initial.put("metadataFilterCount", request.filters().size());
        if (request.modelProfileId() != null) initial.put("modelProfileId", request.modelProfileId());
        return repository.createRun(organizationId, datasetId, Map.copyOf(initial));
    }

    private EvaluationRun createQueuedAgenticRetrievalRun(
            UUID organizationId,
            UUID datasetId,
            StartEvaluationRunRequest request
    ) {
        var initial = new LinkedHashMap<String, Object>();
        initial.put("execution", "AGENTIC_RETRIEVAL_ONLY");
        initial.put("requestedMode", RunMode.DEEP.name());
        initial.put("judgeMode", EvaluationJudgeMode.NONE.name());
        initial.put("answerGenerationSkipped", true);
        initial.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
        initial.put("documentIds", request.scope().documentIds());
        initial.put("metadataFilterCount", request.filters().size());
        if (request.modelProfileId() != null) initial.put("modelProfileId", request.modelProfileId());
        return repository.createRun(organizationId, datasetId, Map.copyOf(initial));
    }

    private EvaluationRun createQueuedRoutingRun(
            UUID organizationId,
            UUID datasetId,
            StartEvaluationRunRequest request
    ) {
        var initial = new LinkedHashMap<String, Object>();
        initial.put("execution", "ROUTING_ONLY");
        initial.put("requestedMode", RunMode.AUTO.name());
        initial.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
        initial.put("documentIds", request.scope().documentIds());
        initial.put("metadataFilterCount", request.filters().size());
        if (request.modelProfileId() != null) initial.put("modelProfileId", request.modelProfileId());
        return repository.createRun(organizationId, datasetId, Map.copyOf(initial));
    }

    private void saveRequestSnapshot(UUID runId, StartEvaluationRunRequest request, String execution) {
        if (attempts != null) attempts.saveRequestSnapshot(runId, requestSnapshot(request, execution));
    }

    private Map<String, Object> requestSnapshot(StartEvaluationRunRequest request, String execution) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("execution", execution);
        snapshot.put("mode", request.mode().name());
        snapshot.put("knowledgeBaseIds", request.scope().knowledgeBaseIds());
        snapshot.put("documentIds", request.scope().documentIds());
        snapshot.put("filters", request.filters());
        if (request.modelProfileId() != null) snapshot.put("modelProfileId", request.modelProfileId());
        snapshot.put("judgeMode", request.judgeMode().name());
        return Map.copyOf(snapshot);
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void validateBundle(UUID organizationId, EvaluationDatasetBundle bundle) {
        if (!EvaluationDatasetBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported evaluation dataset schema: " + bundle.schemaVersion());
        }
        var questions = new java.util.HashSet<String>();
        var documentIds = new LinkedHashSet<UUID>();
        for (var entry : bundle.cases()) {
            var normalized = entry.question().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (!questions.add(normalized)) {
                throw new IllegalArgumentException("Evaluation bundle contains duplicate questions");
            }
            if (entry.metadata().toString().length() > 20_000) {
                throw new IllegalArgumentException("Evaluation case metadata is too large");
            }
            documentIds.addAll(entry.expectedDocumentIds());
            documentIds.addAll(metadataDocumentIds(entry.metadata(), "forbiddenDocumentIds"));
        }
        validateConversationMetadata(bundle.cases().stream()
                .map(EvaluationDatasetBundle.CaseEntry::metadata).toList());
        validateOwnedDocuments(organizationId, List.copyOf(documentIds));
    }

    private void validateConversationCases(List<EvaluationCase> cases) {
        validateConversationMetadata(cases.stream().map(EvaluationCase::metadata).toList());
    }

    private void validateConversationMetadata(List<Map<String, Object>> metadataValues) {
        var nextTurns = new LinkedHashMap<String, Integer>();
        for (var metadata : metadataValues) {
            var conversation = conversationSpec(metadata);
            if (conversation == null) continue;
            int expectedTurn = nextTurns.getOrDefault(conversation.group(), 1);
            if (conversation.turn() != expectedTurn) {
                throw new IllegalArgumentException(
                        "Conversation group " + conversation.group() + " expects turn " + expectedTurn
                                + " but found " + conversation.turn());
            }
            nextTurns.put(conversation.group(), expectedTurn + 1);
        }
    }

    private ConversationSpec conversationSpec(Map<String, Object> metadata) {
        var groupValue = metadata.get(CONVERSATION_GROUP);
        var turnValue = metadata.get(CONVERSATION_TURN);
        if (groupValue == null && turnValue == null) return null;
        if (!(groupValue instanceof String rawGroup) || rawGroup.isBlank()) {
            throw new IllegalArgumentException("conversationGroup must be a non-blank string");
        }
        var group = rawGroup.strip();
        if (group.length() > 80 || group.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("conversationGroup must be at most 80 printable characters");
        }
        if (!(turnValue instanceof Number number)) {
            throw new IllegalArgumentException("conversationTurn must be an integer");
        }
        long turn = number.longValue();
        if (number.doubleValue() != turn || turn < 1 || turn > 100) {
            throw new IllegalArgumentException("conversationTurn must be an integer between 1 and 100");
        }
        return new ConversationSpec(group, (int) turn);
    }

    private List<UUID> metadataDocumentIds(Map<String, Object> metadata, String field) {
        var value = metadata.get(field);
        if (value == null) return List.of();
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException(field + " must be an array of document UUIDs");
        }
        try {
            return values.stream().map(item -> UUID.fromString(String.valueOf(item))).distinct().toList();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " contains an invalid document UUID", failure);
        }
    }

    private void validateOwnedDocuments(UUID organizationId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) return;
        var expected = new LinkedHashSet<>(documentIds);
        var owned = repository.findOwnedDocumentIds(organizationId, List.copyOf(expected));
        expected.removeAll(owned);
        if (!expected.isEmpty()) {
            throw new IllegalArgumentException("Evaluation references documents outside the organization: "
                    + expected.stream().limit(5).toList());
        }
    }

    private EvaluationDatasetView datasetView(UUID organizationId, EvaluationDataset dataset) {
        var cases = repository.findCases(organizationId, dataset.id());
        var runs = repository.findRuns(organizationId, dataset.id());
        return datasetView(dataset, cases.size(), runs);
    }

    private EvaluationDatasetView datasetView(EvaluationDataset dataset, int caseCount, List<EvaluationRun> runs) {
        var lastRun = runs.isEmpty() ? null : runs.getFirst();
        return new EvaluationDatasetView(
                dataset.id(),
                dataset.name(),
                dataset.description(),
                caseCount,
                runs.size(),
                lastRun == null ? null : lastRun.status().name(),
                lastRun == null ? Map.of() : lastRun.aggregateMetrics(),
                dataset.createdAt()
        );
    }

    private EvaluationCaseView caseView(EvaluationCase value) {
        return new EvaluationCaseView(value.id(), value.datasetId(), value.question(), value.expectedAnswer(),
                value.expectedDocumentIds(), value.metadata(), value.position());
    }

    private EvaluationRunView runView(EvaluationRun value) {
        return new EvaluationRunView(value.id(), value.datasetId(), value.status().name(), value.aggregateMetrics(),
                value.startedAt(), value.completedAt(), value.createdAt());
    }

    private EvaluationComparisonView comparisonView(
            EvaluationComparison value,
            EvaluationRun fast,
            EvaluationRun deep
    ) {
        return comparisonView(value, runView(fast), runView(deep));
    }

    private EvaluationComparisonView comparisonView(
            EvaluationComparison value,
            EvaluationRunView fast,
            EvaluationRunView deep
    ) {
        return new EvaluationComparisonView(
                value.id(), value.datasetId(), fast, deep,
                com.yanyue.rag.contract.evaluation.EvaluationJudgeMode.valueOf(value.judgeMode()),
                value.createdAt());
    }

    private String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record CaseMetrics(
            Map<String, Object> values,
            double recallAt5,
            double recallAt10,
            double reciprocalRankAt5,
            double reciprocalRank,
            int hitAt5,
            int hitAt10,
            double answerCoverage,
            long latencyMs,
            boolean hasExpectedDocuments,
            boolean hasExpectedAnswer,
            boolean ragExecuted,
            int citationCount,
            double citationResolvableRate,
            int effectiveLeakCount,
            boolean hasNoAnswerExpectation,
            double noAnswerCorrect
    ) {
    }

    private record ConversationSpec(String group, int turn) {
    }

    private record AgenticCaseExecution(
            UUID ragRunId,
            CaseMetrics metrics,
            Map<String, Object> failureMetrics,
            String error
    ) {
    }

    private record RoutingCaseExecution(Map<String, Object> metrics, String error) {
    }
}
