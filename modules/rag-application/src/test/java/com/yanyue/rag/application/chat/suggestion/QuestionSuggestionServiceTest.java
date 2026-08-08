package com.yanyue.rag.application.chat.suggestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.chat.AutoModeRouter;
import com.yanyue.rag.application.chat.RunCoordinator;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.QuestionSuggestionEmptyReason;
import com.yanyue.rag.contract.chat.QuestionSuggestionRequest;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.model.PipelineConfig;
import com.yanyue.rag.domain.port.QuestionSuggestionBenchmarkPort;
import com.yanyue.rag.domain.port.QuestionSuggestionBenchmarkPort.BenchmarkPool;
import com.yanyue.rag.domain.port.QuestionSuggestionBenchmarkPort.BenchmarkQuestion;
import com.yanyue.rag.domain.port.QuestionSuggestionCachePort;
import com.yanyue.rag.domain.port.QuestionSuggestionCachePort.CachedBatch;
import com.yanyue.rag.domain.port.QuestionSuggestionCachePort.CachedQuestion;
import com.yanyue.rag.domain.port.QuestionSuggestionCatalogPort;
import com.yanyue.rag.domain.port.QuestionSuggestionCatalogPort.Catalog;
import com.yanyue.rag.domain.port.QuestionSuggestionCatalogPort.CatalogQuestion;
import com.yanyue.rag.domain.port.QuestionSuggestionCatalogPort.SupportEvidence;
import com.yanyue.rag.domain.port.QuestionSuggestionContextPort;
import com.yanyue.rag.domain.port.QuestionSuggestionContextPort.EligibilitySnapshot;
import com.yanyue.rag.domain.port.QuestionSuggestionContextPort.SourceExcerpt;
import com.yanyue.rag.domain.port.QuestionSuggestionContextPort.SuggestionContext;
import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.port.StructuredReasoningModelPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuestionSuggestionServiceTest {
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID rerankProfileId = UUID.randomUUID();
    private final UUID configId = UUID.randomUUID();
    private final UUID chunkOne = UUID.randomUUID();
    private final UUID chunkTwo = UUID.randomUUID();
    private final QuestionSuggestionContextPort contexts = mock(QuestionSuggestionContextPort.class);
    private final QuestionSuggestionBenchmarkPort benchmarks = mock(QuestionSuggestionBenchmarkPort.class);
    private final QuestionSuggestionCachePort cache = mock(QuestionSuggestionCachePort.class);
    private final QuestionSuggestionCatalogPort catalogs = mock(QuestionSuggestionCatalogPort.class);
    private final StructuredReasoningModelPort model = mock(StructuredReasoningModelPort.class);
    private final RetrievalPort retrieval = mock(RetrievalPort.class);
    private final RerankModelPort rerank = mock(RerankModelPort.class);
    private final PipelineConfigService pipelineConfigs = mock(PipelineConfigService.class);
    private final MetadataSchemaService metadataSchemas = mock(MetadataSchemaService.class);
    private final AutoModeRouter autoModeRouter = new AutoModeRouter();
    private final RunCoordinator runCoordinator = mock(RunCoordinator.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-03T02:00:00Z"), ZoneOffset.UTC);
    private QuestionSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new QuestionSuggestionService(contexts, benchmarks, cache, catalogs, model, retrieval, rerank,
                pipelineConfigs, metadataSchemas, autoModeRouter, runCoordinator,
                new ObjectMapper(), Runnable::run, clock);
        when(metadataSchemas.validateFilters(eq(organizationId), any(), any())).thenReturn(List.of());
        when(pipelineConfigs.activeModel(organizationId)).thenReturn(config());
        when(contexts.eligibility(any())).thenReturn(new EligibilitySnapshot("revision-1", Set.of(versionId)));
        when(benchmarks.find(any(), any(), any())).thenReturn(Optional.empty());
        when(runCoordinator.selectMode(eq(organizationId), eq(userId), any(CreateRunRequest.class)))
                .thenAnswer(invocation -> {
                    var query = invocation.getArgument(2, CreateRunRequest.class).query();
                    var mode = query.startsWith("快速") ? RunMode.FAST : RunMode.DEEP;
                    return new RunCoordinator.Selection(mode, "test-route", false);
                });
        when(cache.find(anyString())).thenReturn(Optional.empty());
        when(catalogs.find(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void fastModeUsesOnlySingleIntentBenchmarkQuestions() {
        when(benchmarks.find(eq(organizationId), eq(List.of(knowledgeBaseId)), any()))
                .thenReturn(Optional.of(benchmarkPool()));

        var response = service.suggest(organizationId, userId, request(RunMode.FAST, false));

        assertNull(response.emptyReason());
        assertEquals(4, response.suggestions().size());
        assertEquals(Set.of("快速问题1？", "快速问题2？", "快速问题3？", "快速问题4？"),
                response.suggestions().stream().map(value -> value.text()).collect(java.util.stream.Collectors.toSet()));
        verify(catalogs, never()).find(any(), any(), any(), any());
        verifyNoOnlineGeneration();
    }

    @Test
    void deepModeUsesOnlyMultiGoalBenchmarkQuestions() {
        when(benchmarks.find(eq(organizationId), eq(List.of(knowledgeBaseId)), any()))
                .thenReturn(Optional.of(benchmarkPool()));

        var response = service.suggest(organizationId, userId, request(RunMode.DEEP, false));

        assertNull(response.emptyReason());
        assertEquals(4, response.suggestions().size());
        assertEquals(Set.of("深度问题1？", "深度问题2？", "深度问题3？", "深度问题4？"),
                response.suggestions().stream().map(value -> value.text()).collect(java.util.stream.Collectors.toSet()));
        verify(catalogs, never()).find(any(), any(), any(), any());
        verifyNoOnlineGeneration();
    }

    @Test
    void autoModeBalancesFastAndDeepBenchmarkQuestions() {
        when(benchmarks.find(eq(organizationId), eq(List.of(knowledgeBaseId)), any()))
                .thenReturn(Optional.of(benchmarkPool()));

        var response = service.suggest(organizationId, userId, request(RunMode.AUTO, false));

        assertNull(response.emptyReason());
        assertEquals(4, response.suggestions().size());
        assertEquals(2, response.suggestions().stream().filter(value -> value.text().startsWith("快速")).count());
        assertEquals(2, response.suggestions().stream().filter(value -> value.text().startsWith("深度")).count());
        verify(catalogs, never()).find(any(), any(), any(), any());
        verifyNoOnlineGeneration();
    }

    @Test
    void autoUsesDeepAndReturnsExactScopeCacheWithoutOnlineGeneration() {
        var batch = new CachedBatch(UUID.randomUUID(), List.of(
                new CachedQuestion(UUID.randomUUID(), "Kubernetes认证与授权机制有哪些差异？"),
                new CachedQuestion(UUID.randomUUID(), "Kubernetes API 如何配置最小权限？"),
                new CachedQuestion(UUID.randomUUID(), "Kubernetes API 如何进行身份认证？"),
                new CachedQuestion(UUID.randomUUID(), "Kubernetes API 的访问限制有哪些？")));
        when(cache.find(anyString())).thenReturn(Optional.of(batch));

        var response = service.suggest(organizationId, userId, request(RunMode.AUTO, false));

        assertEquals(RunMode.DEEP, response.effectiveMode());
        assertEquals(batch.batchId(), response.batchId());
        verifyNoOnlineGeneration();
    }

    @Test
    void requestReadsValidatedFastQuestionFromCatalog() {
        when(catalogs.find(organizationId, userId, RunMode.FAST, knowledgeBaseId))
                .thenReturn(Optional.of(catalog(List.of(question("如何配置 Kubernetes API 访问权限？",
                        "HOW_TO", List.of(new SupportEvidence(chunkOne, versionId)))))));

        var response = service.suggest(organizationId, userId, request(RunMode.FAST, false));

        assertNull(response.emptyReason());
        assertEquals(List.of("如何配置 Kubernetes API 访问权限？"),
                response.suggestions().stream().map(value -> value.text()).toList());
        verifyNoOnlineGeneration();
    }

    @Test
    void deepCatalogQuestionStillRequiresTwoEligibleEvidenceChunks() {
        when(catalogs.find(organizationId, userId, RunMode.DEEP, knowledgeBaseId))
                .thenReturn(Optional.of(catalog(List.of(question("认证与授权机制在不同场景下有哪些差异？",
                        "COMPARE", List.of(new SupportEvidence(chunkOne, versionId)))))));

        var response = service.suggest(organizationId, userId, request(RunMode.DEEP, false));

        assertEquals(QuestionSuggestionEmptyReason.INSUFFICIENT_EVIDENCE, response.emptyReason());
        verifyNoOnlineGeneration();
    }

    @Test
    void missingCatalogReturnsBuildingImmediately() {
        var response = service.suggest(organizationId, userId, request(RunMode.FAST, false));

        assertEquals(QuestionSuggestionEmptyReason.CATALOG_BUILDING, response.emptyReason());
        verifyNoOnlineGeneration();
    }

    @Test
    void metadataEligibilityRemovesQuestionsWhoseSupportingVersionIsNotVisible() {
        when(contexts.eligibility(any())).thenReturn(new EligibilitySnapshot("filtered", Set.of(UUID.randomUUID())));
        when(catalogs.find(organizationId, userId, RunMode.FAST, knowledgeBaseId))
                .thenReturn(Optional.of(catalog(List.of(question("如何配置 Kubernetes API 访问权限？",
                        "HOW_TO", List.of(new SupportEvidence(chunkOne, versionId)))))));

        var response = service.suggest(organizationId, userId, request(RunMode.FAST, false));

        assertEquals(QuestionSuggestionEmptyReason.INSUFFICIENT_EVIDENCE, response.emptyReason());
        verifyNoOnlineGeneration();
    }

    @Test
    void backgroundWarmGeneratesValidatesAndStoresCatalog() {
        when(contexts.load(any(), anyInt(), anyInt())).thenReturn(context());
        when(model.completeJson(any(), anyString(), anyString(), anyString(), any(Duration.class),
                anyInt(), anyInt(), anyDouble())).thenReturn("""
                {"questions":[{"text":"Kubernetes认证与授权机制在不同场景下有哪些差异？","kind":"COMPARE"}]}
                """);
        var first = hit(chunkOne);
        var second = hit(chunkTwo);
        when(retrieval.keywordSearch(anyString(), any(), anyInt())).thenReturn(List.of(first, second));
        when(retrieval.semanticSearch(anyString(), any(), anyInt(), anyInt())).thenReturn(List.of(first, second));
        when(rerank.rerank(any(), anyString(), any(), anyInt(), any(Duration.class))).thenReturn(List.of(
                new RerankModelPort.RerankScore(0, 0.92),
                new RerankModelPort.RerankScore(1, 0.88)));

        service.warmCatalog(organizationId, userId, RunMode.DEEP, knowledgeBaseId);

        verify(catalogs).save(eq(organizationId), eq(userId), eq(RunMode.DEEP), eq(knowledgeBaseId),
                any(Catalog.class), eq(Duration.ofDays(7)));
    }

    private void verifyNoOnlineGeneration() {
        verify(model, never()).completeJson(any(), anyString(), anyString(), anyString(),
                any(Duration.class), anyInt(), anyInt(), anyDouble());
        verify(retrieval, never()).keywordSearch(anyString(), any(), anyInt());
        verify(rerank, never()).rerank(any(), anyString(), any(), anyInt(), any(Duration.class));
    }

    private QuestionSuggestionRequest request(RunMode mode, boolean refresh) {
        return new QuestionSuggestionRequest(mode, new KnowledgeScope(List.of(knowledgeBaseId), List.of()),
                List.of(), refresh, null);
    }

    private Catalog catalog(List<CatalogQuestion> questions) {
        return new Catalog("revision-1", configId, clock.instant(), questions);
    }

    private CatalogQuestion question(String text, String kind, List<SupportEvidence> evidence) {
        return new CatalogQuestion(UUID.randomUUID(), text, kind, 0.95, evidence);
    }

    private BenchmarkPool benchmarkPool() {
        return new BenchmarkPool("benchmark-revision", List.of(
                benchmarkQuestion("快速问题1？", "keyword_sparse", "kubernetes", 1),
                benchmarkQuestion("快速问题2？", "semantic_paraphrase", "ant-design", 2),
                benchmarkQuestion("快速问题3？", "keyword_sparse", "apache-doris", 3),
                benchmarkQuestion("快速问题4？", "semantic_paraphrase", "openeuler", 4),
                benchmarkQuestion("深度问题1？", "multi_intent", "kubernetes", 5),
                benchmarkQuestion("深度问题2？", "query_decomposition", "ant-design", 6),
                benchmarkQuestion("深度问题3？", "multi_intent", "apache-doris", 7),
                benchmarkQuestion("深度问题4？", "query_decomposition", "openeuler", 8)));
    }

    private BenchmarkQuestion benchmarkQuestion(
            String text,
            String challengeType,
            String sourceProject,
            int position
    ) {
        return new BenchmarkQuestion(UUID.randomUUID(), text, challengeType, sourceProject, position);
    }

    private SuggestionContext context() {
        return new SuggestionContext("revision-1", List.of(new SourceExcerpt(
                knowledgeBaseId, "技术知识库", documentId, versionId, "Kubernetes API 指南",
                "Kubernetes API 支持认证、授权、TLS 配置和最小权限访问。")));
    }

    private RetrievalHit hit(UUID chunkId) {
        return new RetrievalHit(chunkId, null, documentId, versionId, "Kubernetes API 指南",
                "Kubernetes API 支持认证、授权、TLS 配置和最小权限访问。", 0.8, List.of("test"));
    }

    private PipelineConfig config() {
        var now = clock.instant();
        return new PipelineConfig(configId, organizationId, "active", "v8", "prompt-v1",
                profileId, profileId, rerankProfileId, 20, 20, 30, 12, 8, 8_000,
                0.3, 60, true, now, now);
    }
}
