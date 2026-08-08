package com.yanyue.rag.application.chat.v4;

import com.yanyue.rag.application.chat.ReciprocalRankFusion;
import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v4.GoalPlan;
import com.yanyue.rag.domain.agent.v4.RepairTarget;
import com.yanyue.rag.domain.agent.v4.ResearchHealth;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v4.SearchQuery;
import com.yanyue.rag.domain.chunking.v4.CandidateSpan;
import com.yanyue.rag.domain.chunking.v4.CandidateSpanBuilder;
import com.yanyue.rag.domain.port.AgenticV4ContextPort;
import com.yanyue.rag.domain.port.AgenticV4ArtifactPort;
import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class GoalResearchService {
    private final RetrievalPort retrieval;
    private final RerankModelPort rerank;
    private final AgenticV4ContextPort contexts;
    private final AgenticV4ArtifactPort artifacts;
    private final DeepReadReasoner deepRead;
    private final EvidenceAcceptanceService acceptance;
    private final Executor executor;
    private final Clock clock;
    private final CandidateSpanBuilder spanBuilder = new CandidateSpanBuilder();
    private final java.util.concurrent.Semaphore searchSlots = new java.util.concurrent.Semaphore(6, true);
    private final java.util.concurrent.Semaphore rerankSlots = new java.util.concurrent.Semaphore(3, true);
    private final java.util.concurrent.Semaphore deepReadSlots = new java.util.concurrent.Semaphore(3, true);

    public GoalResearchService(
            RetrievalPort retrieval,
            RerankModelPort rerank,
            AgenticV4ContextPort contexts,
            AgenticV4ArtifactPort artifacts,
            DeepReadReasoner deepRead,
            EvidenceAcceptanceService acceptance,
            @Qualifier("ragRunExecutor") Executor executor,
            Clock clock
    ) {
        this.retrieval = retrieval;
        this.rerank = rerank;
        this.contexts = contexts;
        this.artifacts = artifacts;
        this.deepRead = deepRead;
        this.acceptance = acceptance;
        this.executor = executor;
        this.clock = clock;
    }

    public ResearchResult research(
            UUID profileId,
            UUID rerankProfileId,
            UUID runId,
            String objective,
            GoalPlan goal,
            ResearchPhase phase,
            List<SearchQuery> queries,
            List<RepairTarget> repairTargets,
            RetrievalScope scope,
            int keywordTopK,
            int semanticTopK,
            int rerankCandidateLimit,
            double minimumRerankScore,
            AgentBudgetLedger ledger,
            GoalEvidencePool pool
    ) {
        var searchTaskIds = queries.stream().map(SearchQuery::queryId).toList();
        try {
            var searchFutures = queries.stream().map(query -> CompletableFuture.supplyAsync(
                    () -> search(runId, query, scope, keywordTopK, semanticTopK, ledger), executor)).toList();
            var outcomes = await(searchFutures, ledger);
            boolean searchFailed = outcomes.stream().anyMatch(SearchOutcome::failed);
            var rankings = outcomes.stream().map(SearchOutcome::hits).toList();
            var candidates = phase == ResearchPhase.REPAIR
                    ? ReciprocalRankFusion.fuse(rankings, 24)
                    : rankings.stream().findFirst().orElse(List.of());
            candidates = deduplicate(candidates).stream().limit(12).toList();
            var rerankOutcome = rerank(goal, queries, candidates, rerankProfileId,
                    Math.min(6, rerankCandidateLimit), minimumRerankScore, ledger, runId, phase);
            var reranked = rerankOutcome.hits();
            if (reranked.isEmpty()) {
                return new ResearchResult(goal.id(), phase, List.of(), searchFailed
                        ? ResearchHealth.EVIDENCE_MAY_BE_HIDDEN : ResearchHealth.COMPLETED_EMPTY, searchFailed,
                        searchTaskIds, null);
            }

            var parentReservation = ledger.reserve("parents:" + phase + ":" + goal.id(),
                    Map.of(BudgetDimension.PARENT_READ, 4L), clock.instant());
            artifacts.reserveOperation(runId, goal.id(), phase.name(), "PARENT_READ", parentReservation);
            ledger.markDispatched(parentReservation.reservationId(), clock.instant());
            if (!artifacts.claimOperation(parentReservation.reservationId())) {
                ledger.fail(parentReservation.reservationId(), Map.of(), clock.instant());
                throw new IllegalStateException("父块读取预算动作无法 claim");
            }
            AgenticV4ContextPort.ParentLoadResult loaded;
            try {
                var childCandidates = childCandidates(queries, reranked);
                loaded = contexts.loadParentContexts(childCandidates, scope, 4, remaining(ledger));
                artifacts.completeOperation(parentReservation.reservationId(), true, null);
                ledger.succeed(parentReservation.reservationId(), Map.of(), clock.instant());
            } catch (RuntimeException failure) {
                artifacts.completeOperation(parentReservation.reservationId(), false,
                        failure.getClass().getSimpleName());
                ledger.fail(parentReservation.reservationId(), Map.of(), clock.instant());
                return new ResearchResult(goal.id(), phase, List.of(),
                        ResearchHealth.EVIDENCE_MAY_BE_HIDDEN, true, searchTaskIds, null);
            }
            var targetRequirementIds = queries.stream().flatMap(value -> value.targetRequirementIds().stream())
                    .distinct().toList();
            var focus = goal.question() + " " + goal.requirements().stream()
                    .filter(value -> targetRequirementIds.contains(value.id()))
                    .map(value -> value.description()).collect(java.util.stream.Collectors.joining(" "));
            var spans = selectSpans(loaded.contexts(), focus);
            if (spans.isEmpty()) {
                var hidden = searchFailed || loaded.evidenceMayBeHidden();
                return new ResearchResult(goal.id(), phase, List.of(), hidden
                        ? ResearchHealth.EVIDENCE_MAY_BE_HIDDEN : ResearchHealth.COMPLETED_EMPTY, hidden,
                        searchTaskIds, null);
            }
            var spanReservation = ledger.reserve("spans:" + phase + ":" + goal.id(),
                    Map.of(BudgetDimension.CANDIDATE_SPAN_OFFERED, (long) spans.size()), clock.instant());
            ledger.markDispatched(spanReservation.reservationId(), clock.instant());
            ledger.succeed(spanReservation.reservationId(), Map.of(), clock.instant());
            var selections = withPermit(deepReadSlots, ledger, () -> deepRead.select(
                    profileId, runId, objective, goal, phase, targetRequirementIds,
                    repairTargets, queries, spans, ledger));
            var evidence = acceptance.accept(goal, phase, repairTargets, queries, loaded.contexts(), spans,
                    selections, scope, pool);
            var hidden = searchFailed || loaded.evidenceMayBeHidden();
            var health = hidden && evidence.isEmpty() ? ResearchHealth.EVIDENCE_MAY_BE_HIDDEN
                    : hidden || rerankOutcome.failed() ? ResearchHealth.DEGRADED_NON_BLOCKING
                    : evidence.isEmpty() ? ResearchHealth.COMPLETED_EMPTY : ResearchHealth.COMPLETED_WITH_EVIDENCE;
            return new ResearchResult(goal.id(), phase, evidence, health,
                    hidden, searchTaskIds, deepReadLogicalCallId(runId, phase, goal.id()));
        } catch (java.util.concurrent.CancellationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Goal 研究已取消");
            }
            return new ResearchResult(goal.id(), phase, List.of(), ResearchHealth.EVIDENCE_MAY_BE_HIDDEN, true,
                    searchTaskIds, null);
        }
    }

    private SearchOutcome search(
            UUID runId,
            SearchQuery query,
            RetrievalScope scope,
            int keywordTopK,
            int semanticTopK,
            AgentBudgetLedger ledger
    ) {
        var usage = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
        usage.put(BudgetDimension.PHYSICAL_SEARCH, 1L);
        if (query.searchMode() == SearchMode.SEMANTIC) {
            usage.put(BudgetDimension.SEMANTIC_EMBEDDING_OPERATION, 1L);
        }
        var reservation = ledger.reserve("search:" + query.queryId(), usage, clock.instant());
        artifacts.reserveSearch(runId, reservation, query);
        ledger.markDispatched(reservation.reservationId(), clock.instant());
        if (!artifacts.claimSearch(runId, reservation.reservationId())) {
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            return new SearchOutcome(query, List.of(), true);
        }
        try {
            var hits = withPermit(searchSlots, ledger, () -> query.searchMode() == SearchMode.KEYWORD
                    ? retrieval.keywordSearch(query.text(), scope, Math.min(12, keywordTopK))
                    : retrieval.semanticSearchStrict(query.text(), scope, Math.min(12, semanticTopK), 4,
                            remaining(ledger)));
            artifacts.saveRetrievalCandidates(runId, query, hits);
            artifacts.completeSearch(runId, reservation.reservationId(), true, hits.size(), null);
            ledger.succeed(reservation.reservationId(), Map.of(), clock.instant());
            return new SearchOutcome(query, hits, false);
        } catch (RuntimeException failure) {
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            artifacts.completeSearch(runId, reservation.reservationId(), false, 0,
                    failure.getClass().getSimpleName());
            return new SearchOutcome(query, List.of(), true);
        }
    }

    private RerankOutcome rerank(
            GoalPlan goal,
            List<SearchQuery> queries,
            List<RetrievalHit> candidates,
            UUID profileId,
            int topK,
            double minimumScore,
            AgentBudgetLedger ledger,
            UUID runId,
            ResearchPhase phase
    ) {
        if (candidates.isEmpty()) return new RerankOutcome(List.of(), false);
        var reservation = ledger.reserve("rerank:" + phase + ":" + goal.id(),
                Map.of(BudgetDimension.RERANK_CALL, 1L), clock.instant());
        artifacts.reserveOperation(runId, goal.id(), phase.name(), "RERANK", reservation);
        ledger.markDispatched(reservation.reservationId(), clock.instant());
        if (!artifacts.claimOperation(reservation.reservationId())) {
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            throw new IllegalStateException("Rerank 预算动作无法 claim");
        }
        try {
            var focus = goal.question() + " " + goal.requirements().stream()
                    .filter(value -> queries.stream().anyMatch(query -> query.targetRequirementIds().contains(value.id())))
                    .map(value -> value.description()).collect(java.util.stream.Collectors.joining(" "));
            var scores = withPermit(rerankSlots, ledger, () -> rerank.rerank(profileId, focus,
                    candidates.stream().map(RetrievalHit::text).toList(), topK, remaining(ledger)));
            var selected = new ArrayList<RetrievalHit>();
            var indexes = new java.util.HashSet<Integer>();
            for (var score : scores.stream().sorted(Comparator.comparingDouble(
                    RerankModelPort.RerankScore::score).reversed()).toList()) {
                if (score.index() < 0 || score.index() >= candidates.size() || !indexes.add(score.index())) continue;
                var hit = candidates.get(score.index());
                if (score.score() >= minimumScore || selected.isEmpty()) {
                    selected.add(hit.withScore(score.score(), append(hit.sources(), "rerank")));
                }
                if (selected.size() >= topK) break;
            }
            artifacts.completeOperation(reservation.reservationId(), true, null);
            ledger.succeed(reservation.reservationId(), Map.of(), clock.instant());
            return new RerankOutcome(selected.isEmpty()
                    ? candidates.stream().limit(topK).toList() : List.copyOf(selected), false);
        } catch (RuntimeException failure) {
            artifacts.completeOperation(reservation.reservationId(), false,
                    failure.getClass().getSimpleName());
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            return new RerankOutcome(candidates.stream().limit(topK).toList(), true);
        }
    }

    private List<AgenticV4ContextPort.ChildCandidate> childCandidates(
            List<SearchQuery> queries,
            List<RetrievalHit> hits
    ) {
        var result = new ArrayList<AgenticV4ContextPort.ChildCandidate>();
        for (var hit : hits) {
            for (var query : queries) {
                var source = query.searchMode().name().toLowerCase(java.util.Locale.ROOT);
                if (hit.sources().contains(source) || hit.sources().contains("rrf") || queries.size() == 1) {
                    result.add(new AgenticV4ContextPort.ChildCandidate(query.queryId(), hit));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<CandidateSpan> selectSpans(
            List<com.yanyue.rag.domain.chunking.v4.ParentContext> contexts,
            String focus
    ) {
        var result = new ArrayList<CandidateSpan>();
        for (var context : contexts.stream().sorted(Comparator.comparingDouble(
                com.yanyue.rag.domain.chunking.v4.ParentContext::retrievalScore).reversed()).toList()) {
            result.addAll(spanBuilder.build(context, focus).stream().limit(2).toList());
        }
        return result.stream().limit(8).toList();
    }

    private List<RetrievalHit> deduplicate(List<RetrievalHit> candidates) {
        var result = new LinkedHashMap<String, RetrievalHit>();
        candidates.forEach(hit -> result.putIfAbsent(hit.documentVersionId() + ":" + hit.chunkId(), hit));
        return List.copyOf(result.values());
    }

    private List<String> append(List<String> values, String value) {
        var copy = new ArrayList<>(values);
        if (!copy.contains(value)) copy.add(value);
        return List.copyOf(copy);
    }

    private UUID deepReadLogicalCallId(UUID runId, ResearchPhase phase, UUID goalId) {
        return UUID.nameUUIDFromBytes((runId + ":deep-read:" + phase + ":" + goalId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private java.time.Duration remaining(AgentBudgetLedger ledger) {
        var remaining = java.time.Duration.between(clock.instant(), ledger.deadline())
                .minusSeconds(2);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalStateException("Run Deadline 已耗尽");
        }
        return remaining;
    }

    private <T> T withPermit(
            java.util.concurrent.Semaphore semaphore,
            AgentBudgetLedger ledger,
            java.util.function.Supplier<T> action
    ) {
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(remaining(ledger).toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            if (!acquired) throw new IllegalStateException("等待并发槽位时 Run Deadline 已耗尽");
            return action.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("等待并发槽位时被取消");
        } finally {
            if (acquired) semaphore.release();
        }
    }

    private <T> List<T> await(List<CompletableFuture<T>> futures, AgentBudgetLedger ledger) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(
                    remaining(ledger).toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            return futures.stream().map(CompletableFuture::join).toList();
        } catch (java.util.concurrent.TimeoutException failure) {
            futures.forEach(value -> value.cancel(true));
            throw new IllegalStateException("并发检索等待超过 Run Deadline", failure);
        } catch (InterruptedException failure) {
            futures.forEach(value -> value.cancel(true));
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("并发检索等待被取消");
        } catch (java.util.concurrent.ExecutionException failure) {
            var cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("并发检索执行失败", cause);
        }
    }

    private record SearchOutcome(SearchQuery query, List<RetrievalHit> hits, boolean failed) { }

    private record RerankOutcome(List<RetrievalHit> hits, boolean failed) {
        private RerankOutcome {
            hits = List.copyOf(hits);
        }
    }

    public record ResearchResult(
            UUID goalId,
            ResearchPhase phase,
            List<AcceptedEvidence> acceptedEvidence,
            ResearchHealth health,
            boolean mayHaveHiddenEvidence,
            List<UUID> searchTaskIds,
            UUID deepReadLogicalCallId
    ) {
        public ResearchResult {
            acceptedEvidence = List.copyOf(acceptedEvidence);
            searchTaskIds = List.copyOf(searchTaskIds);
        }

        public ResearchResult(
                UUID goalId,
                ResearchPhase phase,
                List<AcceptedEvidence> acceptedEvidence,
                ResearchHealth health,
                boolean mayHaveHiddenEvidence
        ) {
            this(goalId, phase, acceptedEvidence, health, mayHaveHiddenEvidence, List.of(), null);
        }
    }
}
