package com.yanyue.rag.infrastructure.agent.v5;

import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v5.GoalRankedCandidate;
import com.yanyue.rag.domain.agent.v5.RouteObservation;
import com.yanyue.rag.domain.port.AgenticV5RankingPort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class JooqAgenticV5RankingAdapter implements AgenticV5RankingPort {
    private final DSLContext dsl;

    public JooqAgenticV5RankingAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void saveGoalRankedCandidates(
            UUID runId,
            UUID goalId,
            int goalOrder,
            ResearchPhase phase,
            List<GoalRankedCandidate> candidates
    ) {
        requireIdentity(runId, goalId, goalOrder, phase, candidates);
        dsl.transaction(configuration -> {
            var tx = DSL.using(configuration);
            lockAndValidateCandidateSet(tx, runId, goalId, phase, candidates);
            for (var candidate : candidates) {
                saveCandidate(tx, goalOrder, candidate);
                for (var route : candidate.routeObservations()) {
                    validateRouteSource(tx, candidate, route);
                    saveRoute(tx, candidate, route);
                }
            }
            validatePersistedCandidateSet(tx, runId, goalId, phase, candidates);
        });
    }

    @Override
    public List<GoalRankedCandidate> loadGoalRankedCandidates(
            UUID runId,
            UUID goalId,
            ResearchPhase phase
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(phase, "phase");
        var routes = loadRoutes(runId, goalId, phase);
        return dsl.fetch("""
                SELECT run_id, goal_id, phase, chunk_id, document_id, document_version_id,
                       best_raw_rank, best_raw_score, rrf_rank, rrf_score,
                       rerank_rank, rerank_score, rerank_fallback, selected_for_parent,
                       retrieval_sources
                FROM agent_goal_ranked_candidate
                WHERE run_id = ? AND goal_id = ? AND phase = ?
                ORDER BY rrf_rank, chunk_id
                """, runId, goalId, phase.name()).map(record -> candidate(record, routes));
    }

    private void requireIdentity(
            UUID runId,
            UUID goalId,
            int goalOrder,
            ResearchPhase phase,
            List<GoalRankedCandidate> candidates
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(candidates, "candidates");
        if (goalOrder < 1 || goalOrder > 3) {
            throw new IllegalArgumentException("Goal 顺序必须位于 1 到 3");
        }
        var chunkIds = new LinkedHashSet<UUID>();
        for (var candidate : candidates) {
            if (!runId.equals(candidate.runId()) || !goalId.equals(candidate.goalId())
                    || phase != candidate.phase()) {
                throw new IllegalArgumentException("排名候选不属于给定 Run、Goal 或阶段");
            }
            if (!chunkIds.add(candidate.chunkId())) {
                throw new IllegalArgumentException("同一 Goal 排名不能包含重复 Chunk");
            }
        }
    }

    private void lockAndValidateCandidateSet(
            DSLContext tx,
            UUID runId,
            UUID goalId,
            ResearchPhase phase,
            List<GoalRankedCandidate> candidates
    ) {
        var persisted = new LinkedHashSet<>(tx.fetch("""
                SELECT chunk_id FROM agent_goal_ranked_candidate
                WHERE run_id = ? AND goal_id = ? AND phase = ?
                FOR UPDATE
                """, runId, goalId, phase.name()).getValues("chunk_id", UUID.class));
        if (!persisted.isEmpty() && !persisted.equals(chunkIds(candidates))) {
            throw new IllegalStateException("已持久化的 Goal 排名候选集合不可修改");
        }
    }

    private void saveCandidate(DSLContext tx, int goalOrder, GoalRankedCandidate candidate) {
        var sources = sourceNames(candidate.retrievalSources());
        int inserted = tx.execute("""
                INSERT INTO agent_goal_ranked_candidate
                    (run_id, goal_id, goal_order, phase, chunk_id, document_id, document_version_id,
                     best_raw_rank, best_raw_score, rrf_rank, rrf_score, rerank_rank, rerank_score,
                     rerank_fallback, selected_for_parent, retrieval_sources)
                SELECT ?, ?, ?, ?, chunk.id, version.document_id, version.id,
                       ?, ?, ?, ?, ?, ?, ?, ?, ?
                FROM chunk
                JOIN document_version version ON version.id = chunk.document_version_id
                JOIN document ON document.id = version.document_id
                JOIN rag_run run ON run.id = ? AND run.organization_id = document.organization_id
                WHERE chunk.id = ? AND version.id = ? AND version.document_id = ?
                ON CONFLICT (run_id, goal_id, phase, chunk_id) DO NOTHING
                """, candidate.runId(), candidate.goalId(), goalOrder, candidate.phase().name(),
                candidate.bestRawRank(), candidate.bestRawScore(), candidate.rrfRank(), candidate.rrfScore(),
                candidate.rerankRank(), candidate.rerankScore(), candidate.rerankFallback(),
                candidate.selectedForParent(), sources, candidate.runId(), candidate.chunkId(),
                candidate.documentVersionId(), candidate.documentId());
        if (inserted == 0) validatePersistedCandidate(tx, goalOrder, candidate, sources);
    }

    private void validatePersistedCandidate(
            DSLContext tx,
            int goalOrder,
            GoalRankedCandidate candidate,
            String[] sources
    ) {
        var existing = tx.fetchOne("""
                SELECT goal_order, document_id, document_version_id, best_raw_rank, best_raw_score,
                       rrf_rank, rrf_score, rerank_rank, rerank_score, rerank_fallback,
                       selected_for_parent, retrieval_sources
                FROM agent_goal_ranked_candidate
                WHERE run_id = ? AND goal_id = ? AND phase = ? AND chunk_id = ?
                """, candidate.runId(), candidate.goalId(), candidate.phase().name(), candidate.chunkId());
        if (existing == null) {
            throw new IllegalArgumentException("Chunk、Document 与 DocumentVersion 关系不一致");
        }
        boolean same = goalOrder == existing.get("goal_order", Integer.class)
                && candidate.documentId().equals(existing.get("document_id", UUID.class))
                && candidate.documentVersionId().equals(existing.get("document_version_id", UUID.class))
                && candidate.bestRawRank() == existing.get("best_raw_rank", Integer.class)
                && same(candidate.bestRawScore(), existing.get("best_raw_score", Double.class))
                && candidate.rrfRank() == existing.get("rrf_rank", Integer.class)
                && same(candidate.rrfScore(), existing.get("rrf_score", Double.class))
                && Objects.equals(candidate.rerankRank(), existing.get("rerank_rank", Integer.class))
                && same(candidate.rerankScore(), existing.get("rerank_score", Double.class))
                && candidate.rerankFallback() == existing.get("rerank_fallback", Boolean.class)
                && candidate.selectedForParent() == existing.get("selected_for_parent", Boolean.class)
                && Set.of(sources).equals(Set.of(existing.get("retrieval_sources", String[].class)));
        if (!same) throw new IllegalStateException("同一 Goal 排名身份对应的数据不一致");
    }

    private void validateRouteSource(
            DSLContext tx,
            GoalRankedCandidate candidate,
            RouteObservation route
    ) {
        String expectedRole = candidate.phase().name() + "_" + route.searchMode().name();
        boolean valid = tx.fetchOptional("""
                SELECT 1
                FROM agent_retrieval_task task
                JOIN retrieval_query_candidate raw
                  ON raw.retrieval_task_id = task.id
                 AND raw.run_id = task.run_id
                 AND raw.goal_id = task.sub_question_id
                 AND raw.phase = task.research_phase
                 AND raw.chunk_id = ?
                 AND raw.retrieval_source = ?
                 AND raw.candidate_rank = ?
                 AND raw.score = ?
                WHERE task.id = ? AND task.run_id = ? AND task.sub_question_id = ?
                  AND task.research_phase = ? AND task.search_mode = ? AND task.query_role = ?
                """, candidate.chunkId(), route.searchMode().name(), route.rawRank(), route.rawScore(),
                route.queryId(), candidate.runId(), candidate.goalId(), candidate.phase().name(),
                route.searchMode().name(), expectedRole).isPresent();
        if (!valid) {
            throw new IllegalArgumentException("RouteObservation 与原始 Query 候选不一致");
        }
    }

    private void saveRoute(DSLContext tx, GoalRankedCandidate candidate, RouteObservation route) {
        int inserted = tx.execute("""
                INSERT INTO agent_goal_ranked_candidate_route
                    (run_id, goal_id, phase, chunk_id, query_id, search_mode, raw_rank, raw_score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, goal_id, phase, chunk_id, query_id) DO NOTHING
                """, candidate.runId(), candidate.goalId(), candidate.phase().name(), candidate.chunkId(),
                route.queryId(), route.searchMode().name(), route.rawRank(), route.rawScore());
        if (inserted == 1) return;
        var existing = tx.fetchOne("""
                SELECT search_mode, raw_rank, raw_score
                FROM agent_goal_ranked_candidate_route
                WHERE run_id = ? AND goal_id = ? AND phase = ? AND chunk_id = ? AND query_id = ?
                """, candidate.runId(), candidate.goalId(), candidate.phase().name(), candidate.chunkId(),
                route.queryId());
        if (existing == null
                || !route.searchMode().name().equals(existing.get("search_mode", String.class))
                || route.rawRank() != existing.get("raw_rank", Integer.class)
                || !same(route.rawScore(), existing.get("raw_score", Double.class))) {
            throw new IllegalStateException("同一 RouteObservation 身份对应的数据不一致");
        }
    }

    private void validatePersistedCandidateSet(
            DSLContext tx,
            UUID runId,
            UUID goalId,
            ResearchPhase phase,
            List<GoalRankedCandidate> candidates
    ) {
        var persisted = new LinkedHashSet<>(tx.fetch("""
                SELECT chunk_id FROM agent_goal_ranked_candidate
                WHERE run_id = ? AND goal_id = ? AND phase = ?
                """, runId, goalId, phase.name()).getValues("chunk_id", UUID.class));
        if (!persisted.equals(chunkIds(candidates))) {
            throw new IllegalStateException("Goal 排名候选集合未能原子持久化");
        }
    }

    private LinkedHashMap<UUID, List<RouteObservation>> loadRoutes(
            UUID runId,
            UUID goalId,
            ResearchPhase phase
    ) {
        var routes = new LinkedHashMap<UUID, List<RouteObservation>>();
        dsl.fetch("""
                SELECT chunk_id, query_id, search_mode, raw_rank, raw_score
                FROM agent_goal_ranked_candidate_route
                WHERE run_id = ? AND goal_id = ? AND phase = ?
                ORDER BY chunk_id, search_mode, query_id
                """, runId, goalId, phase.name()).forEach(record -> routes.computeIfAbsent(
                record.get("chunk_id", UUID.class), ignored -> new ArrayList<>()).add(new RouteObservation(
                record.get("query_id", UUID.class),
                SearchMode.valueOf(record.get("search_mode", String.class)),
                record.get("raw_rank", Integer.class), record.get("raw_score", Double.class))));
        return routes;
    }

    private GoalRankedCandidate candidate(
            Record record,
            LinkedHashMap<UUID, List<RouteObservation>> routesByChunk
    ) {
        UUID chunkId = record.get("chunk_id", UUID.class);
        var routes = List.copyOf(routesByChunk.getOrDefault(chunkId, List.of()));
        if (routes.isEmpty()) throw new IllegalStateException("最终排名候选缺少 RouteObservation");
        var taskIds = new LinkedHashSet<UUID>();
        var sources = new LinkedHashSet<SearchMode>();
        routes.forEach(route -> {
            taskIds.add(route.queryId());
            sources.add(route.searchMode());
        });
        var storedSources = Arrays.stream(record.get("retrieval_sources", String[].class))
                .map(SearchMode::valueOf).collect(java.util.stream.Collectors.toSet());
        if (!storedSources.equals(sources)) {
            throw new IllegalStateException("最终排名候选的来源投影与 RouteObservation 不一致");
        }
        return new GoalRankedCandidate(
                record.get("run_id", UUID.class), record.get("goal_id", UUID.class),
                ResearchPhase.valueOf(record.get("phase", String.class)), chunkId,
                record.get("document_id", UUID.class), record.get("document_version_id", UUID.class),
                taskIds, sources, routes, record.get("best_raw_rank", Integer.class),
                record.get("best_raw_score", Double.class), record.get("rrf_rank", Integer.class),
                record.get("rrf_score", Double.class), record.get("rerank_rank", Integer.class),
                record.get("rerank_score", Double.class), record.get("rerank_fallback", Boolean.class),
                record.get("selected_for_parent", Boolean.class));
    }

    private Set<UUID> chunkIds(List<GoalRankedCandidate> candidates) {
        return candidates.stream().map(GoalRankedCandidate::chunkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String[] sourceNames(Set<SearchMode> sources) {
        return sources.stream().map(Enum::name).sorted(Comparator.naturalOrder()).toArray(String[]::new);
    }

    private boolean same(Double left, Double right) {
        return left == null ? right == null : right != null && Double.compare(left, right) == 0;
    }
}
