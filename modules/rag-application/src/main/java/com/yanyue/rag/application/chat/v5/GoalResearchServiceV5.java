package com.yanyue.rag.application.chat.v5;

import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v4.BudgetReservation;
import com.yanyue.rag.domain.agent.v4.ResearchHealth;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;
import com.yanyue.rag.domain.agent.v5.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v5.GoalPlan;
import com.yanyue.rag.domain.agent.v5.GoalRankedCandidate;
import com.yanyue.rag.domain.agent.v5.QueryPair;
import com.yanyue.rag.domain.agent.v5.RouteObservation;
import com.yanyue.rag.domain.agent.v5.SearchQuery;
import com.yanyue.rag.domain.chunking.v4.CandidateSpan;
import com.yanyue.rag.domain.chunking.v4.CandidateSpanBuilder;
import com.yanyue.rag.domain.port.AgenticV4ArtifactPort;
import com.yanyue.rag.domain.port.AgenticV4ContextPort;
import com.yanyue.rag.domain.port.AgenticV5RankingPort;
import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class GoalResearchServiceV5 {
    private static final int RRF_K = 60;

    private final RetrievalPort retrieval;
    private final RerankModelPort rerank;
    private final AgenticV4ContextPort contexts;
    private final AgenticV4ArtifactPort artifacts;
    private final AgenticV5RankingPort rankings;
    private final DeepReadReasonerV5 deepRead;
    private final EvidenceAcceptanceServiceV5 acceptance;
    private final Executor executor;
    private final Clock clock;
    private final CandidateSpanBuilder spanBuilder = new CandidateSpanBuilder();
    private final java.util.concurrent.Semaphore searchSlots = new java.util.concurrent.Semaphore(6, true);
    private final java.util.concurrent.Semaphore rerankSlots = new java.util.concurrent.Semaphore(3, true);
    private final java.util.concurrent.Semaphore deepReadSlots = new java.util.concurrent.Semaphore(3, true);

    public GoalResearchServiceV5(
            RetrievalPort retrieval,
            RerankModelPort rerank,
            AgenticV4ContextPort contexts,
            AgenticV4ArtifactPort artifacts,
            AgenticV5RankingPort rankings,
            DeepReadReasonerV5 deepRead,
            EvidenceAcceptanceServiceV5 acceptance,
            @Qualifier("ragRunExecutor") Executor executor,
            Clock clock
    ) {
        this.retrieval = retrieval;
        this.rerank = rerank;
        this.contexts = contexts;
        this.artifacts = artifacts;
        this.rankings = rankings;
        this.deepRead = deepRead;
        this.acceptance = acceptance;
        this.executor = executor;
        this.clock = clock;
    }

    public ResearchResult research(
            UUID profileId,
            UUID rerankProfileId,
            UUID runId,
            int goalOrder,
            String objective,
            GoalPlan goal,
            QueryPair queryPair,
            RetrievalScope scope,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits,
            GoalEvidencePool pool
    ) {
        var phase = queryPair.phase();
        var searchTaskIds = queryPair.queries().stream().map(SearchQuery::queryId).toList();
        try {
            var reservations = reservePair(runId, queryPair, ledger);
            var futures = queryPair.queries().stream().map(query -> CompletableFuture.supplyAsync(
                    () -> search(runId, query, reservations.get(query.queryId()), scope, ledger, limits), executor)).toList();
            var outcomes = await(futures, ledger);
            boolean anyFailed = outcomes.stream().anyMatch(SearchOutcome::failed);
            boolean allFailed = outcomes.stream().allMatch(SearchOutcome::failed);
            var fused = fuse(outcomes, limits.retrieval().rrfCandidateLimit());
            if (fused.isEmpty()) return empty(goal, phase, searchTaskIds, anyFailed, allFailed);

            var rerankOutcome = rerank(goal, queryPair, fused, rerankProfileId, runId, ledger, limits);
            var parentSelection = selectParents(rerankOutcome.finalCandidates(), limits.retrieval().parentLimitPerGoalPhase());
            var rankedArtifacts = rankedArtifacts(runId, goal.id(), phase, fused, rerankOutcome, parentSelection);
            rankings.saveGoalRankedCandidates(runId, goal.id(), goalOrder, phase, rankedArtifacts);

            var parentReservation = ledger.reserve("parents:" + phase + ":" + goal.id(),
                    Map.of(BudgetDimension.PARENT_READ, (long) limits.retrieval().parentLimitPerGoalPhase()),
                    clock.instant());
            artifacts.reserveOperation(runId, goal.id(), phase.name(), "PARENT_READ", parentReservation);
            ledger.markDispatched(parentReservation.reservationId(), clock.instant());
            if (!artifacts.claimOperation(parentReservation.reservationId())) {
                ledger.fail(parentReservation.reservationId(), Map.of(), clock.instant());
                throw new IllegalStateException("父块读取预算动作无法 claim");
            }
            AgenticV4ContextPort.ParentLoadResult loaded;
            try {
                loaded = contexts.loadParentContexts(childCandidates(parentSelection, rerankOutcome, fused), scope,
                        limits.retrieval().parentLimitPerGoalPhase(), remaining(ledger));
                artifacts.completeOperation(parentReservation.reservationId(), true, null);
                ledger.succeed(parentReservation.reservationId(), Map.of(), clock.instant());
            } catch (RuntimeException failure) {
                artifacts.completeOperation(parentReservation.reservationId(), false, failure.getClass().getSimpleName());
                ledger.fail(parentReservation.reservationId(), Map.of(), clock.instant());
                return new ResearchResult(goal.id(), phase, List.of(), ResearchHealth.EVIDENCE_MAY_BE_HIDDEN,
                        true, searchTaskIds, null);
            }

            var targetIds = queryPair.keywordQuery().targetRequirementIds();
            var focus = goal.question() + " " + goal.requirements().stream()
                    .filter(value -> targetIds.contains(value.id())).map(value -> value.description())
                    .collect(java.util.stream.Collectors.joining(" "));
            var spans = selectSpans(loaded.contexts(), focus, limits.retrieval().candidateSpanLimit());
            if (spans.isEmpty()) return empty(goal, phase, searchTaskIds,
                    anyFailed || loaded.evidenceMayBeHidden(), allFailed);
            var spanReservation = ledger.reserve("spans:" + phase + ":" + goal.id(),
                    Map.of(BudgetDimension.CANDIDATE_SPAN_OFFERED, (long) spans.size()), clock.instant());
            ledger.markDispatched(spanReservation.reservationId(), clock.instant());
            ledger.succeed(spanReservation.reservationId(), Map.of(), clock.instant());

            var selections = withPermit(deepReadSlots, ledger, () -> deepRead.select(profileId, runId, objective,
                    goal, phase, targetIds, queryPair.queries(), spans, ledger, limits));
            var evidence = acceptance.accept(goal, phase, queryPair.queries(), loaded.contexts(), spans,
                    selections, scope, pool);
            var hidden = anyFailed || loaded.evidenceMayBeHidden();
            var health = hidden && evidence.isEmpty() ? ResearchHealth.EVIDENCE_MAY_BE_HIDDEN
                    : hidden || rerankOutcome.fallback() ? ResearchHealth.DEGRADED_NON_BLOCKING
                    : evidence.isEmpty() ? ResearchHealth.COMPLETED_EMPTY : ResearchHealth.COMPLETED_WITH_EVIDENCE;
            return new ResearchResult(goal.id(), phase, evidence, health, hidden, searchTaskIds,
                    stableId(runId + ":deep-read:" + phase + ":" + goal.id()));
        } catch (java.util.concurrent.CancellationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Goal 研究已取消");
            return new ResearchResult(goal.id(), phase, List.of(), ResearchHealth.EVIDENCE_MAY_BE_HIDDEN,
                    true, searchTaskIds, null);
        }
    }

    private Map<UUID, BudgetReservation> reservePair(UUID runId, QueryPair pair, AgentBudgetLedger ledger) {
        synchronized (ledger) {
            var result = new LinkedHashMap<UUID, BudgetReservation>();
            try {
                for (var query : pair.queries()) {
                    var usage = new EnumMap<BudgetDimension, Long>(BudgetDimension.class);
                    usage.put(BudgetDimension.PHYSICAL_SEARCH, 1L);
                    if (query.searchMode() == SearchMode.SEMANTIC) {
                        usage.put(BudgetDimension.SEMANTIC_EMBEDDING_OPERATION, 1L);
                    }
                    var reservation = ledger.reserve("search:" + query.queryId(), usage, clock.instant());
                    artifacts.reserveSearch(runId, reservation, legacy(query));
                    result.put(query.queryId(), reservation);
                }
                return Map.copyOf(result);
            } catch (RuntimeException failure) {
                result.values().forEach(value -> {
                    try {
                        ledger.release(value.reservationId(), clock.instant());
                    } catch (RuntimeException ignored) {
                        // 原子 Pair 预留失败时尽力释放尚未派发的预算。
                    }
                });
                throw failure;
            }
        }
    }

    private SearchOutcome search(
            UUID runId,
            SearchQuery query,
            BudgetReservation reservation,
            RetrievalScope scope,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits
    ) {
        ledger.markDispatched(reservation.reservationId(), clock.instant());
        if (!artifacts.claimSearch(runId, reservation.reservationId())) {
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            return new SearchOutcome(query, List.of(), true);
        }
        try {
            var hits = withPermit(searchSlots, ledger, () -> query.searchMode() == SearchMode.KEYWORD
                    ? retrieval.keywordSearch(query.text(), scope, limits.retrieval().keywordTopK())
                    : retrieval.semanticSearchStrict(query.text(), scope, limits.retrieval().semanticTopK(), 4,
                            remaining(ledger)));
            artifacts.saveRetrievalCandidates(runId, legacy(query), hits);
            artifacts.completeSearch(runId, reservation.reservationId(), true, hits.size(), null);
            ledger.succeed(reservation.reservationId(), Map.of(), clock.instant());
            return new SearchOutcome(query, List.copyOf(hits), false);
        } catch (RuntimeException failure) {
            artifacts.completeSearch(runId, reservation.reservationId(), false, 0, failure.getClass().getSimpleName());
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            return new SearchOutcome(query, List.of(), true);
        }
    }

    private List<FusedCandidate> fuse(List<SearchOutcome> outcomes, int maximum) {
        var fused = new LinkedHashMap<String, MutableFusion>();
        for (var outcome : outcomes) {
            var seen = new LinkedHashSet<String>();
            int rank = 0;
            for (var hit : outcome.hits()) {
                var key = hit.documentVersionId() + ":" + hit.chunkId();
                if (!seen.add(key)) continue;
                rank++;
                var route = new RouteObservation(outcome.query().queryId(), outcome.query().searchMode(), rank,
                        hit.score());
                fused.computeIfAbsent(key, ignored -> new MutableFusion(hit)).add(route, hit);
            }
        }
        var ordered = fused.values().stream().map(MutableFusion::freeze).sorted(Comparator
                .comparingDouble(FusedCandidate::rrfScore).reversed()
                .thenComparingInt(FusedCandidate::bestRawRank)
                .thenComparing(Comparator.comparingDouble(FusedCandidate::bestRawScore).reversed())
                .thenComparing(value -> value.hit().chunkId())).limit(maximum).toList();
        var result = new ArrayList<FusedCandidate>();
        for (int index = 0; index < ordered.size(); index++) result.add(ordered.get(index).withRrfRank(index + 1));
        return List.copyOf(result);
    }

    private RerankOutcome rerank(
            GoalPlan goal,
            QueryPair pair,
            List<FusedCandidate> fused,
            UUID profileId,
            UUID runId,
            AgentBudgetLedger ledger,
            AgenticV5Limits limits
    ) {
        var input = fused.stream().limit(limits.retrieval().rerankInputLimit()).toList();
        var reservation = ledger.reserve("rerank:" + pair.phase() + ":" + goal.id(),
                Map.of(BudgetDimension.RERANK_CALL, 1L), clock.instant());
        artifacts.reserveOperation(runId, goal.id(), pair.phase().name(), "RERANK", reservation);
        ledger.markDispatched(reservation.reservationId(), clock.instant());
        if (!artifacts.claimOperation(reservation.reservationId())) {
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            throw new IllegalStateException("Rerank 预算动作无法 claim");
        }
        try {
            var focus = goal.question() + " " + goal.requirements().stream()
                    .filter(value -> pair.keywordQuery().targetRequirementIds().contains(value.id()))
                    .map(value -> value.description()).collect(java.util.stream.Collectors.joining(" "));
            var scores = withPermit(rerankSlots, ledger, () -> rerank.rerank(profileId, focus,
                    input.stream().map(value -> value.hit().text()).toList(),
                    limits.retrieval().rerankOutputLimit(), remaining(ledger)));
            var ranked = new ArrayList<Reranked>();
            var indexes = new LinkedHashSet<Integer>();
            for (var score : scores.stream().sorted(Comparator.comparingDouble(
                    RerankModelPort.RerankScore::score).reversed()).toList()) {
                if (score.index() < 0 || score.index() >= input.size() || !indexes.add(score.index())) continue;
                ranked.add(new Reranked(input.get(score.index()), ranked.size() + 1, score.score()));
                if (ranked.size() >= limits.retrieval().rerankOutputLimit()) break;
            }
            artifacts.completeOperation(reservation.reservationId(), true, null);
            ledger.succeed(reservation.reservationId(), Map.of(), clock.instant());
            if (!ranked.isEmpty()) return new RerankOutcome(ranked, false);
        } catch (RuntimeException failure) {
            artifacts.completeOperation(reservation.reservationId(), false, failure.getClass().getSimpleName());
            ledger.fail(reservation.reservationId(), Map.of(), clock.instant());
            return fallback(input, limits.retrieval().rerankOutputLimit());
        }
        return fallback(input, limits.retrieval().rerankOutputLimit());
    }

    private RerankOutcome fallback(List<FusedCandidate> input, int maximum) {
        return new RerankOutcome(input.stream().limit(maximum)
                .map(value -> new Reranked(value, null, null)).toList(), true);
    }

    private Set<UUID> selectParents(List<Reranked> candidates, int maximum) {
        var selected = new LinkedHashSet<UUID>();
        var parents = new HashSet<String>();
        var perDocument = new HashMap<UUID, Integer>();
        for (var value : candidates) {
            var hit = value.candidate().hit();
            if (hit.parentChunkId() == null || perDocument.getOrDefault(hit.documentId(), 0) >= 2) continue;
            var parentKey = hit.documentVersionId() + ":" + hit.parentChunkId();
            if (!parents.add(parentKey)) continue;
            selected.add(hit.chunkId());
            perDocument.merge(hit.documentId(), 1, Integer::sum);
            if (selected.size() >= maximum) break;
        }
        return Set.copyOf(selected);
    }

    private List<GoalRankedCandidate> rankedArtifacts(
            UUID runId,
            UUID goalId,
            ResearchPhase phase,
            List<FusedCandidate> fused,
            RerankOutcome rerankOutcome,
            Set<UUID> selected
    ) {
        var reranked = rerankOutcome.finalCandidates().stream().collect(java.util.stream.Collectors.toMap(
                value -> value.candidate().hit().chunkId(), value -> value));
        return fused.stream().map(value -> {
            var hit = value.hit();
            var ranked = reranked.get(hit.chunkId());
            var taskIds = value.routes().stream().map(RouteObservation::queryId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var sources = value.routes().stream().map(RouteObservation::searchMode)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return new GoalRankedCandidate(runId, goalId, phase, hit.chunkId(), hit.documentId(),
                    hit.documentVersionId(), taskIds, sources, value.routes(), value.bestRawRank(),
                    value.bestRawScore(), value.rrfRank(), value.rrfScore(),
                    ranked == null ? null : ranked.rank(), ranked == null ? null : ranked.score(),
                    rerankOutcome.fallback(), selected.contains(hit.chunkId()));
        }).toList();
    }

    private List<AgenticV4ContextPort.ChildCandidate> childCandidates(
            Set<UUID> selected,
            RerankOutcome rerankOutcome,
            List<FusedCandidate> fused
    ) {
        var byChunk = fused.stream().collect(java.util.stream.Collectors.toMap(value -> value.hit().chunkId(), value -> value));
        var scoreByChunk = rerankOutcome.finalCandidates().stream().collect(java.util.stream.Collectors.toMap(
                value -> value.candidate().hit().chunkId(), value -> value.score() == null
                        ? value.candidate().rrfScore() : value.score()));
        var result = new ArrayList<AgenticV4ContextPort.ChildCandidate>();
        for (var chunkId : selected) {
            var candidate = byChunk.get(chunkId);
            if (candidate == null) continue;
            var hit = candidate.hit().withScore(scoreByChunk.getOrDefault(chunkId, candidate.rrfScore()),
                    candidate.routes().stream().map(value -> value.searchMode().name().toLowerCase()).toList());
            candidate.routes().forEach(route -> result.add(new AgenticV4ContextPort.ChildCandidate(route.queryId(), hit)));
        }
        return List.copyOf(result);
    }

    private List<CandidateSpan> selectSpans(
            List<com.yanyue.rag.domain.chunking.v4.ParentContext> contexts,
            String focus,
            int maximum
    ) {
        var result = new ArrayList<CandidateSpan>();
        for (var context : contexts.stream().sorted(Comparator.comparingDouble(
                com.yanyue.rag.domain.chunking.v4.ParentContext::retrievalScore).reversed()).toList()) {
            result.addAll(spanBuilder.build(context, focus).stream().limit(2).toList());
        }
        return result.stream().limit(maximum).toList();
    }

    private ResearchResult empty(
            GoalPlan goal,
            ResearchPhase phase,
            List<UUID> taskIds,
            boolean hidden,
            boolean allFailed
    ) {
        return new ResearchResult(goal.id(), phase, List.of(), hidden || allFailed
                ? ResearchHealth.EVIDENCE_MAY_BE_HIDDEN : ResearchHealth.COMPLETED_EMPTY,
                hidden || allFailed, taskIds, null);
    }

    private com.yanyue.rag.domain.agent.v4.SearchQuery legacy(SearchQuery query) {
        return new com.yanyue.rag.domain.agent.v4.SearchQuery(query.queryId(), query.goalId(), query.phase(),
                com.yanyue.rag.domain.agent.v4.SearchQueryRole.valueOf(query.role().name()), query.text(),
                query.searchMode(), query.targetRequirementIds());
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

    private java.time.Duration remaining(AgentBudgetLedger ledger) {
        var value = java.time.Duration.between(clock.instant(), ledger.deadline()).minusSeconds(2);
        if (value.isZero() || value.isNegative()) throw new IllegalStateException("Run Deadline 已耗尽");
        return value;
    }

    private UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record SearchOutcome(SearchQuery query, List<RetrievalHit> hits, boolean failed) { }

    private static final class MutableFusion {
        private RetrievalHit bestHit;
        private final List<RouteObservation> routes = new ArrayList<>();

        private MutableFusion(RetrievalHit hit) {
            bestHit = hit;
        }

        private void add(RouteObservation route, RetrievalHit hit) {
            routes.add(route);
            if (hit.score() > bestHit.score()) bestHit = hit;
        }

        private FusedCandidate freeze() {
            int bestRank = routes.stream().mapToInt(RouteObservation::rawRank).min().orElseThrow();
            double bestScore = routes.stream().mapToDouble(RouteObservation::rawScore).max().orElseThrow();
            double rrfScore = routes.stream().mapToDouble(value -> 1.0 / (RRF_K + value.rawRank())).sum();
            return new FusedCandidate(bestHit, List.copyOf(routes), bestRank, bestScore, 0, rrfScore);
        }
    }

    private record FusedCandidate(
            RetrievalHit hit,
            List<RouteObservation> routes,
            int bestRawRank,
            double bestRawScore,
            int rrfRank,
            double rrfScore
    ) {
        private FusedCandidate withRrfRank(int rank) {
            return new FusedCandidate(hit, routes, bestRawRank, bestRawScore, rank, rrfScore);
        }
    }

    private record Reranked(FusedCandidate candidate, Integer rank, Double score) { }

    private record RerankOutcome(List<Reranked> finalCandidates, boolean fallback) {
        private RerankOutcome {
            finalCandidates = List.copyOf(finalCandidates);
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
    }
}
