package com.yanyue.rag.application.chat;

import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.application.chat.v8.ConversationalAnswerService;
import com.yanyue.rag.application.chat.v8.KnowledgeDemandClassifier;
import com.yanyue.rag.application.pipeline.AssistantProfileService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v8.AgenticV8Limits;
import com.yanyue.rag.domain.model.AssistantProfile;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.CitationValidationPort;
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
import java.time.Instant;
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
import org.springframework.stereotype.Service;

@Service
public class FastRagPipeline {
    private static final int ROUTING_KEYWORD_TOP_K = 30;
    private static final int ROUTING_SEMANTIC_TOP_K = 30;
    private static final int ROUTING_RRF_LIMIT = 40;
    private final RetrievalPort retrieval;
    private final QueryRewriteModelPort queryRewrite;
    private final RerankModelPort rerank;
    private final StreamingAnswerModelPort answerModel;
    private final PipelineConfigService pipelineConfigs;
    private final ConversationMemoryPort memory;
    private final MemoryFactRepository memoryFacts;
    private final CitationPort citations;
    private final CitationValidationPort citationValidation;
    private final RetrievalTracePort traces;
    private final RunRecordPort runRecords;
    private final MetadataSchemaService metadataSchemas;
    private final RunEventHub events;
    private final Executor executor;
    private final Clock clock;
    private final RagTelemetry telemetry;
    private final ConversationalAnswerService conversationalAnswers;
    private final KnowledgeDemandClassifier knowledgeDemand;
    private final AssistantProfileService assistantProfiles;

    public FastRagPipeline(
            RetrievalPort retrieval,
            QueryRewriteModelPort queryRewrite,
            RerankModelPort rerank,
            StreamingAnswerModelPort answerModel,
            PipelineConfigService pipelineConfigs,
            ConversationMemoryPort memory,
            MemoryFactRepository memoryFacts,
            CitationPort citations,
            CitationValidationPort citationValidation,
            RetrievalTracePort traces,
            RunRecordPort runRecords,
            MetadataSchemaService metadataSchemas,
            RunEventHub events,
            @Qualifier("ragRunExecutor") Executor executor,
            Clock clock,
            RagTelemetry telemetry,
            ConversationalAnswerService conversationalAnswers,
            KnowledgeDemandClassifier knowledgeDemand,
            AssistantProfileService assistantProfiles
    ) {
        this.retrieval = retrieval;
        this.queryRewrite = queryRewrite;
        this.rerank = rerank;
        this.answerModel = answerModel;
        this.pipelineConfigs = pipelineConfigs;
        this.memory = memory;
        this.memoryFacts = memoryFacts;
        this.citations = citations;
        this.citationValidation = citationValidation;
        this.traces = traces;
        this.runRecords = runRecords;
        this.metadataSchemas = metadataSchemas;
        this.events = events;
        this.executor = executor;
        this.clock = clock;
        this.telemetry = telemetry;
        this.conversationalAnswers = conversationalAnswers;
        this.knowledgeDemand = knowledgeDemand;
        this.assistantProfiles = assistantProfiles;
    }

    public String execute(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            CreateRunRequest request
    ) {
        return execute(runId, conversationId, organizationId, userId, request, null);
    }

    public RoutingPreflight prepareRouting(
            UUID organizationId,
            UUID userId,
            CreateRunRequest request
    ) {
        var query = request.query().strip().replaceAll("\\s+", " ");
        var validatedFilters = metadataSchemas.validateFilters(
                organizationId, request.scope().knowledgeBaseIds(), request.filters());
        var scope = userId == null
                ? RetrievalScope.system(organizationId, request.scope().knowledgeBaseIds(),
                        request.scope().documentIds(), validatedFilters, clock.instant())
                : RetrievalScope.forUser(organizationId, userId, request.scope().knowledgeBaseIds(),
                        request.scope().documentIds(), validatedFilters, clock.instant());
        var startedAt = clock.instant();
        var retrieved = retrieve(query, scope, ROUTING_KEYWORD_TOP_K, ROUTING_SEMANTIC_TOP_K);
        var fused = ReciprocalRankFusion.fuse(
                List.of(retrieved.keyword(), retrieved.semantic()), ROUTING_RRF_LIMIT);
        return new RoutingPreflight(query, retrieved.keyword(), retrieved.semantic(), fused, startedAt,
                Duration.between(startedAt, clock.instant()).toMillis());
    }

    public String execute(
            UUID runId,
            UUID conversationId,
            UUID organizationId,
            UUID userId,
            CreateRunRequest request,
            RoutingPreflight routingPreflight
    ) {
        var startedAt = clock.instant();
        var validatedFilters = metadataSchemas.validateFilters(
                organizationId, request.scope().knowledgeBaseIds(), request.filters());
        var config = pipelineConfigs.resolve(organizationId, request.modelProfileId(),
                runRecords.pipelineConfigId(runId).orElse(null));
        var chatProfileId = request.modelProfileId() == null ? config.chatProfileId() : request.modelProfileId();
        runRecords.applyRuntime(runId, config, chatProfileId);
        var assistant = assistantProfiles.forConversation(organizationId, conversationId);
        runRecords.applyAssistantProfile(runId, assistant.id());
        var normalizedQuery = request.query().strip().replaceAll("\\s+", " ");
        var recent = memory.recentMessages(conversationId, 8);
        var demand = knowledgeDemand.classify(normalizedQuery);
        if (demand == ConversationalAnswerService.KnowledgeDemand.NONE) {
            return conversationalAnswer(runId, conversationId, normalizedQuery, normalizedQuery,
                    chatProfileId, assistant, recent, config.temperature(), config.fastTimeoutSeconds(), demand,
                    ConversationalAnswerService.RetrievalHealth.EMPTY);
        }
        events.publish(runId, StreamEventType.QUERY_REWRITE_STARTED,
                Map.of("hasConversationContext", !recent.isEmpty()));
        var rewrite = shouldRewrite(normalizedQuery, recent)
                ? queryRewrite.rewrite(config.queryRewriteProfileId(), normalizedQuery, recent)
                : QueryRewriteModelPort.RewriteResult.unchanged(normalizedQuery, "not-required");
        var rewritten = rewrite.standaloneQuery();
        if (rewrite.rewriteNeeded() && !rewritten.equals(normalizedQuery)) {
            events.publish(runId, StreamEventType.QUERY_REWRITTEN,
                    java.util.Map.of("original", normalizedQuery, "rewritten", rewritten,
                            "resolvedReferences", rewrite.resolvedReferences(), "profileId", config.queryRewriteProfileId()));
        } else if (rewrite.fallbackReason() != null && !"not-required".equals(rewrite.fallbackReason())) {
            events.publish(runId, StreamEventType.QUERY_REWRITTEN, java.util.Map.of(
                    "original", normalizedQuery, "rewritten", normalizedQuery,
                    "fallback", true, "reason", rewrite.fallbackReason(), "profileId", config.queryRewriteProfileId()));
        } else {
            events.publish(runId, StreamEventType.QUERY_REWRITTEN, java.util.Map.of(
                    "original", normalizedQuery, "rewritten", rewritten, "rewriteNeeded", false));
        }

        var scope = RetrievalScope.forUser(organizationId, userId, request.scope().knowledgeBaseIds(),
                request.scope().documentIds(), validatedFilters, clock.instant());
        events.publish(runId, StreamEventType.RETRIEVAL_STARTED,
                java.util.Map.of("query", rewritten, "strategies", List.of("keyword", "semantic"),
                        "keywordTopK", config.keywordTopK(), "semanticTopK", config.semanticTopK(),
                        "reusedRoutingPreflight", canReuse(routingPreflight, rewritten)));

        boolean reusedRoutingPreflight = canReuse(routingPreflight, rewritten);
        var retrievalStarted = reusedRoutingPreflight ? routingPreflight.startedAt() : clock.instant();
        var retrieved = reusedRoutingPreflight
                ? new HybridRetrieval(routingPreflight.keyword(), routingPreflight.semantic())
                : retrieve(rewritten, scope, config.keywordTopK(), config.semanticTopK());
        var keyword = retrieved.keyword();
        var semantic = retrieved.semantic();
        var fused = reusedRoutingPreflight
                ? routingPreflight.fused()
                : ReciprocalRankFusion.fuse(List.of(keyword, semantic), config.rrfCandidateLimit());
        long retrievalLatencyMs = reusedRoutingPreflight
                ? routingPreflight.latencyMs()
                : Duration.between(retrievalStarted, clock.instant()).toMillis();
        events.publish(runId, StreamEventType.RETRIEVAL_RESULT,
                java.util.Map.of("candidateCount", fused.size(), "keywordCount", keyword.size(),
                        "semanticCount", semantic.size(), "top", summaries(fused, 5),
                        "latencyMs", retrievalLatencyMs, "reusedRoutingPreflight", reusedRoutingPreflight));

        var rerankResult = rerank(runId, config.rerankProfileId(), rewritten, fused,
                config.rerankCandidateLimit());
        var relevant = rerankResult.applied()
                ? rerankResult.hits().stream().filter(hit -> hit.score() >= config.minimumRerankScore()).toList()
                : rerankResult.hits();
        if (relevant.isEmpty()) {
            var reason = fused.isEmpty() ? "no-retrieval-candidates" : "rerank-score-below-threshold";
            return noAnswer(runId, conversationId, normalizedQuery, reason, rewritten, startedAt,
                    keyword, semantic, fused, rerankResult, List.of(), chatProfileId, assistant, recent,
                    config.temperature(), config.fastTimeoutSeconds(), demand);
        }

        var expanded = retrieval.expandContext(relevant, config.finalContextGroups());
        var packed = new ContextPackBuilder().build(expanded, config.contextTokenBudget());
        if (packed.isEmpty()) {
            return noAnswer(runId, conversationId, normalizedQuery, "context-pack-empty", rewritten, startedAt,
                    keyword, semantic, fused, rerankResult, List.of(), chatProfileId, assistant, recent,
                    config.temperature(), config.fastTimeoutSeconds(), demand);
        }

        saveTrace(runId, rewritten, retrievalStarted, keyword, semantic, fused, rerankResult, packed);
        var personalization = personalization(organizationId, userId);
        if (!personalization.isEmpty()) {
            events.publish(runId, StreamEventType.MEMORY_APPLIED, Map.of(
                    "count", personalization.size(), "purpose", "personalization", "evidenceEligible", false));
        }
        var answerRequest = new StreamingAnswerModelPort.AnswerRequest(
                normalizedQuery, rewritten,
                packed.stream().map(item -> new StreamingAnswerModelPort.AnswerEvidence(
                        item.evidenceId(), item.hit().documentTitle(), item.hit().documentVersionId(),
                        item.hit().chunkId(), item.hit().text())).toList(),
                personalization,
                config.fastTimeoutSeconds(), config.maxCompletionTokens(), fastInstruction(assistant),
                recent, config.temperature()
        );
        runRecords.markRetrievalHealth(runId, "SUFFICIENT", packed.size());
        runRecords.markAnswerMode(runId, "GROUNDED", "COMPLETED_WITH_EVIDENCE");
        events.publish(runId, StreamEventType.ANSWER_MODE_SELECTED,
                Map.of("mode", "GROUNDED", "retrievalHealth", "SUFFICIENT",
                        "evidenceCount", packed.size()));
        events.publish(runId, StreamEventType.ANSWER_GENERATION_STARTED,
                Map.of("answerMode", "GROUNDED", "evidenceCount", packed.size()));
        var generation = answerModel.generate(chatProfileId, answerRequest,
                delta -> events.publish(runId, StreamEventType.ANSWER_DELTA, java.util.Map.of("text", delta)));
        var answer = verifyCitations(runId, organizationId, userId, generation.content(), packed,
                citationAnchors(relevant, packed));
        memory.append(conversationId, "user", normalizedQuery, runId);
        memory.append(conversationId, "assistant", answer, runId);
        return answer;
    }

    private HybridRetrieval retrieve(String query, RetrievalScope scope, int keywordTopK, int semanticTopK) {
        return telemetry.observe("rag.retrieval.hybrid", Map.of("strategies", "keyword_semantic"), () -> {
            var keywordFuture = CompletableFuture.supplyAsync(
                    () -> retrieval.keywordSearch(query, scope, keywordTopK), executor);
            var semanticFuture = CompletableFuture.supplyAsync(
                    () -> retrieval.semanticSearch(query, scope, semanticTopK, 4), executor);
            return new HybridRetrieval(keywordFuture.join(), semanticFuture.join());
        });
    }

    private boolean canReuse(RoutingPreflight preflight, String query) {
        return preflight != null && preflight.query().equals(query);
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

    private List<java.util.Map<String, Object>> summaries(List<RetrievalHit> hits, int limit) {
        return hits.stream().limit(limit).map(hit -> java.util.Map.<String, Object>of(
                "chunkId", hit.chunkId(),
                "documentTitle", hit.documentTitle(),
                "score", hit.score(),
                "preview", hit.text().substring(0, Math.min(120, hit.text().length()))
        )).toList();
    }

    private RerankResult rerank(UUID runId, UUID profileId, String query, List<RetrievalHit> candidates, int topK) {
        if (candidates.isEmpty()) return new RerankResult(List.of(), Map.of(), true);
        var started = clock.instant();
        try {
            var scores = rerank.rerank(profileId, query, candidates.stream().map(RetrievalHit::text).toList(), topK);
            var scoreMap = new LinkedHashMap<UUID, Double>();
            var ranked = scores.stream().map(score -> {
                var source = candidates.get(score.index());
                scoreMap.put(source.chunkId(), score.score());
                return source.withScore(score.score(), append(source.sources(), "rerank"));
            }).toList();
            events.publish(runId, StreamEventType.RERANK_COMPLETED, java.util.Map.of(
                    "candidateCount", ranked.size(), "profileId", profileId,
                    "latencyMs", Duration.between(started, clock.instant()).toMillis(),
                    "minimumScore", ranked.stream().mapToDouble(RetrievalHit::score).min().orElse(0),
                    "maximumScore", ranked.stream().mapToDouble(RetrievalHit::score).max().orElse(0)));
            return new RerankResult(ranked, Map.copyOf(scoreMap), true);
        } catch (RuntimeException failure) {
            events.publish(runId, StreamEventType.RERANK_SKIPPED, java.util.Map.of(
                    "profileId", profileId, "reason", safeMessage(failure), "fallback", "rrf-order"));
            return new RerankResult(candidates.stream().limit(topK).toList(), Map.of(), false);
        }
    }

    private String verifyCitations(
            UUID runId,
            UUID organizationId,
            UUID userId,
            String answer,
            List<ContextPackBuilder.PackedEvidence> packed,
            Map<String, RetrievalHit> citationAnchors
    ) {
        var evidence = packed.stream().collect(java.util.stream.Collectors.toMap(
                ContextPackBuilder.PackedEvidence::evidenceId, item -> item, (left, right) -> left,
                LinkedHashMap::new));
        var referenced = new java.util.LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern.compile("\\[E(\\d+)]").matcher(answer);
        while (matcher.find()) referenced.add("E" + matcher.group(1));
        if (referenced.isEmpty()) referenced.addAll(evidence.keySet());
        var invalid = new HashSet<String>();
        for (var evidenceId : referenced) {
            var item = evidence.get(evidenceId);
            var hit = item == null ? null : citationAnchors.getOrDefault(evidenceId, item.hit());
            var valid = hit != null && citationValidation.isCurrentlyValid(
                    organizationId, userId, hit, clock.instant());
            events.publish(runId, StreamEventType.CITATION_VERIFIED, java.util.Map.of(
                    "evidenceId", evidenceId, "valid", valid,
                    "reason", valid ? "current-effective-version" : "unknown-or-no-longer-effective"));
            if (!valid) {
                invalid.add(evidenceId);
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
        var verified = answer;
        for (var evidenceId : invalid) verified = verified.replace("[" + evidenceId + "]", "");
        return verified;
    }

    private Map<String, RetrievalHit> citationAnchors(
            List<RetrievalHit> childHits,
            List<ContextPackBuilder.PackedEvidence> packed
    ) {
        var byParent = childHits.stream()
                .filter(hit -> hit.parentChunkId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        RetrievalHit::parentChunkId,
                        hit -> hit,
                        (left, right) -> left.score() >= right.score() ? left : right,
                        LinkedHashMap::new));
        var result = new LinkedHashMap<String, RetrievalHit>();
        for (var item : packed) {
            var context = item.hit();
            var anchor = byParent.get(context.chunkId());
            result.put(item.evidenceId(), anchor == null ? context : anchor);
        }
        return Map.copyOf(result);
    }

    private String noAnswer(
            UUID runId,
            UUID conversationId,
            String normalizedQuery,
            String reason,
            String rewritten,
            Instant startedAt,
            List<RetrievalHit> keyword,
            List<RetrievalHit> semantic,
            List<RetrievalHit> fused,
            RerankResult rerankResult,
            List<ContextPackBuilder.PackedEvidence> packed,
            UUID chatProfileId,
            AssistantProfile assistant,
            List<String> recent,
            double temperature,
            int timeoutSeconds,
            ConversationalAnswerService.KnowledgeDemand demand
    ) {
        runRecords.markNoAnswer(runId, reason);
        saveTrace(runId, rewritten, startedAt, keyword, semantic, fused, rerankResult, packed);
        events.publish(runId, StreamEventType.NO_ANSWER, java.util.Map.of(
                "reason", reason, "retrievalCandidateCount", fused.size(),
                "rerankApplied", rerankResult.applied()));
        var health = rerankResult.applied()
                ? ConversationalAnswerService.RetrievalHealth.EMPTY
                : ConversationalAnswerService.RetrievalHealth.DEGRADED;
        return conversationalAnswer(runId, conversationId, normalizedQuery, rewritten, chatProfileId,
                assistant, recent, temperature, timeoutSeconds, demand, health);
    }

    private String conversationalAnswer(
            UUID runId,
            UUID conversationId,
            String question,
            String standaloneQuery,
            UUID chatProfileId,
            AssistantProfile assistant,
            List<String> recent,
            double temperature,
            int timeoutSeconds,
            ConversationalAnswerService.KnowledgeDemand demand,
            ConversationalAnswerService.RetrievalHealth health
    ) {
        var limits = AgenticV8Limits.defaults();
        var ledger = new AgentBudgetLedger(limits, clock.instant());
        runRecords.markRetrievalHealth(runId, health.name(), 0);
        var result = conversationalAnswers.answer(runId, conversationId, question, standaloneQuery,
                chatProfileId, assistant, recent, temperature, timeoutSeconds, ledger, limits, demand, health);
        runRecords.markAnswerMode(runId, result.answerMode(), "COMPLETED_WITHOUT_EVIDENCE");
        return result.answer();
    }

    private String fastInstruction(AssistantProfile assistant) {
        return """
                你是 VerityForge 的企业知识助手。只能依据本次提供的内部证据回答，不得使用外部知识补足组织事实。
                每个事实性结论都必须使用输入中真实存在的证据编号；不得伪造引用。
                当前组织角色：
                %s
                使用与用户一致的语言，先给直接结论，再给必要解释。
                """.formatted(assistant.roleInstruction()).strip();
    }

    private void saveTrace(
            UUID runId,
            String query,
            Instant started,
            List<RetrievalHit> keyword,
            List<RetrievalHit> semantic,
            List<RetrievalHit> fused,
            RerankResult rerankResult,
            List<ContextPackBuilder.PackedEvidence> packed
    ) {
        var keywordRanks = ranks(keyword);
        var semanticRanks = ranks(semantic);
        var fusedScores = fused.stream().collect(java.util.stream.Collectors.toMap(
                RetrievalHit::chunkId, RetrievalHit::score, (left, right) -> left));
        var acceptedIds = new HashSet<UUID>();
        packed.forEach(item -> {
            acceptedIds.add(item.hit().chunkId());
            if (item.hit().parentChunkId() != null) acceptedIds.add(item.hit().parentChunkId());
        });
        var all = new LinkedHashMap<UUID, RetrievalHit>();
        keyword.forEach(hit -> all.putIfAbsent(hit.chunkId(), hit));
        semantic.forEach(hit -> all.putIfAbsent(hit.chunkId(), hit));
        fused.forEach(hit -> all.putIfAbsent(hit.chunkId(), hit));
        traces.save(runId, query, Duration.between(started, clock.instant()).toMillis(),
                all.values().stream().map(hit -> new RetrievalTracePort.CandidateTrace(
                        hit, keywordRanks.get(hit.chunkId()), semanticRanks.get(hit.chunkId()),
                        fusedScores.get(hit.chunkId()), rerankResult.scores().get(hit.chunkId()),
                        acceptedIds.contains(hit.chunkId()) || (hit.parentChunkId() != null
                                && acceptedIds.contains(hit.parentChunkId()))
                )).toList());
    }

    private Map<UUID, Integer> ranks(List<RetrievalHit> values) {
        var result = new HashMap<UUID, Integer>();
        for (int index = 0; index < values.size(); index++) result.putIfAbsent(values.get(index).chunkId(), index + 1);
        return result;
    }

    private List<String> append(List<String> sources, String value) {
        var result = new ArrayList<>(sources);
        if (!result.contains(value)) result.add(value);
        return List.copyOf(result);
    }

    private String safeMessage(Throwable failure) {
        var message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.substring(0, Math.min(500, message.length()));
    }

    private record RerankResult(List<RetrievalHit> hits, Map<UUID, Double> scores, boolean applied) {
    }

    private record HybridRetrieval(List<RetrievalHit> keyword, List<RetrievalHit> semantic) {
    }

    public record RoutingPreflight(
            String query,
            List<RetrievalHit> keyword,
            List<RetrievalHit> semantic,
            List<RetrievalHit> fused,
            Instant startedAt,
            long latencyMs
    ) {
        public RoutingPreflight {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
            keyword = keyword == null ? List.of() : List.copyOf(keyword);
            semantic = semantic == null ? List.of() : List.copyOf(semantic);
            fused = fused == null ? List.of() : List.copyOf(fused);
            if (startedAt == null) throw new IllegalArgumentException("startedAt is required");
            if (latencyMs < 0) throw new IllegalArgumentException("latencyMs must be >= 0");
        }
    }
}
