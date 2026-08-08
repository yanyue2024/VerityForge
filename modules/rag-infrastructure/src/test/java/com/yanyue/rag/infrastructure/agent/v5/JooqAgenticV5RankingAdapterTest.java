package com.yanyue.rag.infrastructure.agent.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v5.GoalRankedCandidate;
import com.yanyue.rag.domain.agent.v5.RouteObservation;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class JooqAgenticV5RankingAdapterTest {
    private static PostgreSQLContainer postgres;
    private static DSLContext dsl;
    private static JooqAgenticV5RankingAdapter adapter;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        adapter = new JooqAgenticV5RankingAdapter(dsl);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void migrationAcceptsCheckpointFourAndModeSpecificPrimaryRoles() {
        var fixture = fixture("v5-migration");
        dsl.execute("""
                INSERT INTO agent_run_checkpoint (run_id, checkpoint_version, stage, state)
                VALUES (?, 4, 'PRIMARY_RESEARCH', '{}'::jsonb)
                """, fixture.runId());
        var taskId = saveRawCandidate(fixture, fixture.goalId(), fixture.chunkId(),
                SearchMode.KEYWORD, 1, 0.9);

        assertEquals(4, dsl.fetchValue("""
                SELECT checkpoint_version FROM agent_run_checkpoint WHERE run_id = ?
                """, fixture.runId(), Integer.class));
        assertEquals("PRIMARY_KEYWORD", dsl.fetchValue("""
                SELECT query_role FROM agent_retrieval_task WHERE id = ?
                """, taskId, String.class));
        assertThrows(DataAccessException.class, () -> dsl.execute("""
                INSERT INTO agent_run_checkpoint (run_id, checkpoint_version, stage, state)
                VALUES (?, 5, 'PLAN', '{}'::jsonb)
                """, fixture("v5-invalid-checkpoint").runId()));
    }

    @Test
    void savesAndLoadsRankingWithExactDualRouteProvenanceIdempotently() {
        var fixture = fixture("dual-route");
        var keywordId = saveRawCandidate(fixture, fixture.goalId(), fixture.chunkId(),
                SearchMode.KEYWORD, 2, 0.82);
        var semanticId = saveRawCandidate(fixture, fixture.goalId(), fixture.chunkId(),
                SearchMode.SEMANTIC, 5, 0.73);
        var candidate = candidate(fixture, List.of(
                new RouteObservation(keywordId, SearchMode.KEYWORD, 2, 0.82),
                new RouteObservation(semanticId, SearchMode.SEMANTIC, 5, 0.73)),
                1, 0.031, 1, 0.96, false, true);

        adapter.saveGoalRankedCandidates(
                fixture.runId(), fixture.goalId(), 1, ResearchPhase.PRIMARY, List.of(candidate));
        adapter.saveGoalRankedCandidates(
                fixture.runId(), fixture.goalId(), 1, ResearchPhase.PRIMARY, List.of(candidate));
        var loaded = adapter.loadGoalRankedCandidates(
                fixture.runId(), fixture.goalId(), ResearchPhase.PRIMARY);

        assertEquals(1, loaded.size());
        assertEquals(candidate.chunkId(), loaded.getFirst().chunkId());
        assertEquals(candidate.retrievalTaskIds(), loaded.getFirst().retrievalTaskIds());
        assertEquals(candidate.retrievalSources(), loaded.getFirst().retrievalSources());
        assertEquals(2, loaded.getFirst().routeObservations().size());
        assertEquals(1, count("agent_goal_ranked_candidate", fixture.runId()));
        assertEquals(2, count("agent_goal_ranked_candidate_route", fixture.runId()));
    }

    @Test
    void refusesToChangeAnExistingRankOrCandidateSet() {
        var fixture = fixture("immutable-ranking");
        var queryId = saveRawCandidate(fixture, fixture.goalId(), fixture.chunkId(),
                SearchMode.KEYWORD, 1, 0.91);
        var route = new RouteObservation(queryId, SearchMode.KEYWORD, 1, 0.91);
        var candidate = candidate(fixture, List.of(route), 1, 0.02, 1, 0.95, false, true);
        adapter.saveGoalRankedCandidates(
                fixture.runId(), fixture.goalId(), 1, ResearchPhase.PRIMARY, List.of(candidate));

        var changedScore = candidate(fixture, List.of(route), 1, 0.03, 1, 0.95, false, true);
        assertThrows(IllegalStateException.class, () -> adapter.saveGoalRankedCandidates(
                fixture.runId(), fixture.goalId(), 1, ResearchPhase.PRIMARY, List.of(changedScore)));

        var secondChunkId = saveChunk(fixture.versionId(), 1, "第二个候选");
        saveRawHit(fixture, fixture.goalId(), secondChunkId, queryId, SearchMode.KEYWORD, 2, 0.72);
        var second = candidate(fixture.withChunk(secondChunkId),
                List.of(new RouteObservation(queryId, SearchMode.KEYWORD, 2, 0.72)),
                2, 0.01, null, null, false, false);
        assertThrows(IllegalStateException.class, () -> adapter.saveGoalRankedCandidates(
                fixture.runId(), fixture.goalId(), 1, ResearchPhase.PRIMARY, List.of(candidate, second)));
        assertEquals(1, count("agent_goal_ranked_candidate", fixture.runId()));
    }

    @Test
    void rollsBackRankingWhenRouteBelongsToAnotherGoal() {
        var fixture = fixture("cross-goal-route");
        var otherGoalId = UUID.randomUUID();
        var foreignQueryId = saveRawCandidate(fixture, otherGoalId, fixture.chunkId(),
                SearchMode.KEYWORD, 1, 0.88);
        var candidate = candidate(fixture,
                List.of(new RouteObservation(foreignQueryId, SearchMode.KEYWORD, 1, 0.88)),
                1, 0.02, 1, 0.9, false, true);

        assertThrows(IllegalArgumentException.class, () -> adapter.saveGoalRankedCandidates(
                fixture.runId(), fixture.goalId(), 1, ResearchPhase.PRIMARY, List.of(candidate)));
        assertEquals(0, count("agent_goal_ranked_candidate", fixture.runId()));
        assertEquals(0, count("agent_goal_ranked_candidate_route", fixture.runId()));
    }

    @Test
    void preservesV8RrfTopFourteenAsTheFinalFallbackWithoutInventingRerankFields() {
        var fixture = fixture("fallback-ranking");
        var queryId = saveRawCandidate(fixture, fixture.goalId(), fixture.chunkId(),
                SearchMode.SEMANTIC, 3, 0.79);
        var candidate = candidate(fixture,
                List.of(new RouteObservation(queryId, SearchMode.SEMANTIC, 3, 0.79)),
                14, 0.018, null, null, true, true);

        adapter.saveGoalRankedCandidates(
                fixture.runId(), fixture.goalId(), 2, ResearchPhase.PRIMARY, List.of(candidate));
        var loaded = adapter.loadGoalRankedCandidates(
                fixture.runId(), fixture.goalId(), ResearchPhase.PRIMARY).getFirst();

        assertTrue(loaded.rerankFallback());
        assertNull(loaded.rerankRank());
        assertEquals(14, loaded.rrfRank());
        assertTrue(loaded.selectedForParent());
    }

    private static GoalRankedCandidate candidate(
            Fixture fixture,
            List<RouteObservation> routes,
            int rrfRank,
            double rrfScore,
            Integer rerankRank,
            Double rerankScore,
            boolean fallback,
            boolean selected
    ) {
        var queryIds = routes.stream().map(RouteObservation::queryId)
                .collect(java.util.stream.Collectors.toSet());
        var modes = routes.stream().map(RouteObservation::searchMode)
                .collect(java.util.stream.Collectors.toSet());
        int bestRank = routes.stream().mapToInt(RouteObservation::rawRank).min().orElseThrow();
        double bestScore = routes.stream().mapToDouble(RouteObservation::rawScore).max().orElseThrow();
        return new GoalRankedCandidate(
                fixture.runId(), fixture.goalId(), ResearchPhase.PRIMARY, fixture.chunkId(),
                fixture.documentId(), fixture.versionId(), queryIds, modes, routes,
                bestRank, bestScore, rrfRank, rrfScore, rerankRank, rerankScore, fallback, selected);
    }

    private static UUID saveRawCandidate(
            Fixture fixture,
            UUID goalId,
            UUID chunkId,
            SearchMode mode,
            int rawRank,
            double rawScore
    ) {
        var queryId = UUID.randomUUID();
        String role = "PRIMARY_" + mode.name();
        dsl.execute("""
                INSERT INTO agent_retrieval_task
                    (id, run_id, sub_question_id, round_number, query_text, search_mode, status,
                     result_count, research_phase, query_role, normalized_query)
                VALUES (?, ?, ?, 1, ?, ?, 'SUCCEEDED', 1, 'PRIMARY', ?, ?)
                """, queryId, fixture.runId(), goalId, role, mode.name(), role, role.toLowerCase());
        saveRawHit(fixture, goalId, chunkId, queryId, mode, rawRank, rawScore);
        return queryId;
    }

    private static void saveRawHit(
            Fixture fixture,
            UUID goalId,
            UUID chunkId,
            UUID queryId,
            SearchMode mode,
            int rawRank,
            double rawScore
    ) {
        dsl.execute("""
                INSERT INTO retrieval_query_candidate
                    (retrieval_task_id, run_id, goal_id, phase, chunk_id, candidate_rank,
                     score, retrieval_source)
                VALUES (?, ?, ?, 'PRIMARY', ?, ?, ?, ?)
                """, queryId, fixture.runId(), goalId, chunkId, rawRank, rawScore, mode.name());
    }

    private static Fixture fixture(String prefix) {
        var organizationId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var goalId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        dsl.execute("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, prefix);
        dsl.execute("""
                INSERT INTO rag_run
                    (id, organization_id, requested_mode, selected_mode, query_text, status,
                     pipeline_version, prompt_version, started_at)
                VALUES (?, ?, 'DEEP', 'DEEP', ?, 'RUNNING', 'agentic-rag-v5', 'agentic-rag-v5', now())
                """, runId, organizationId, prefix);
        dsl.execute("""
                INSERT INTO knowledge_base
                    (id, organization_id, name, chunk_policy, created_at, updated_at)
                VALUES (?, ?, ?, '{}'::jsonb, now(), now())
                """, knowledgeBaseId, organizationId, prefix);
        dsl.execute("""
                INSERT INTO document
                    (id, knowledge_base_id, organization_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                """, documentId, knowledgeBaseId, organizationId, prefix);
        dsl.execute("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_name, content_hash, status, published_at)
                VALUES (?, ?, 1, ?, ?, 'PUBLISHED', now())
                """, versionId, documentId, prefix + ".md", "a".repeat(64));
        dsl.execute("UPDATE document SET current_version_id = ? WHERE id = ?", versionId, documentId);
        var chunkId = saveChunk(versionId, 0, "部署步骤和限制");
        return new Fixture(runId, goalId, documentId, versionId, chunkId);
    }

    private static UUID saveChunk(UUID versionId, int order, String text) {
        var chunkId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO chunk
                    (id, document_version_id, chunk_type, order_index, chunk_text, embedding_text,
                     estimated_tokens, chunk_hash, chunk_policy_version)
                VALUES (?, ?, 'CHILD', ?, ?, ?, 8, ?, 'test-v5')
                """, chunkId, versionId, order, text, text,
                sha256(text));
        return chunkId;
    }

    private static String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static int count(String table, UUID runId) {
        return dsl.fetchCount(DSL.table(DSL.name(table)), DSL.field("run_id").eq(runId));
    }

    private record Fixture(UUID runId, UUID goalId, UUID documentId, UUID versionId, UUID chunkId) {
        Fixture withChunk(UUID replacement) {
            return new Fixture(runId, goalId, documentId, versionId, replacement);
        }
    }
}
