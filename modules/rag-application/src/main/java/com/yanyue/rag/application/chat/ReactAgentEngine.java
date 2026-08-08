package com.yanyue.rag.application.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.StreamEventType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.agent.react.KnowledgeReferenceSource;
import com.yanyue.rag.domain.agent.react.ReactCheckpoint;
import com.yanyue.rag.domain.agent.react.ReactKnowledgeReference;
import com.yanyue.rag.domain.agent.react.ReactStep;
import com.yanyue.rag.domain.agent.react.ReactStepStatus;
import com.yanyue.rag.domain.agent.react.ReactToolCall;
import com.yanyue.rag.domain.agent.react.ReactToolCallStatus;
import com.yanyue.rag.domain.port.AgentChatModelPort;
import com.yanyue.rag.domain.port.AgentKnowledgeToolPort;
import com.yanyue.rag.domain.port.AgentReactPersistencePort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.CitationValidationPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.port.RunRecordPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * WeKnora-v2 compatible ReAct loop.  The model owns the decision to search,
 * deep-read, or stop; the server only owns scope, budgets, and validation.
 */
@Service
public class ReactAgentEngine {
    private static final String PIPELINE_VERSION = "agentic-react-v1";
    private static final String PROMPT_VERSION = "weknora-progressive-rag-v1";
    /**
     * A profile-scoped strategy flag.  It is intentionally not a pipeline
     * default: the local Qwen profile can opt into a stronger evidence loop
     * while GPT-compatible production profiles retain the original ReAct stop
     * semantics.
     */
    private static final String LOCAL_QWEN_STRATEGY = "qwen3-local-deep-v2";
    private static final int MAX_OUTPUT_CHARS = 16_000;
    private static final Pattern KB_TAG = Pattern.compile(
            "<kb\\s+doc=\"([^\"]+)\"\\s+chunk_id=\"([^\"]+)\"\\s*/>");

    private final ObjectMapper objectMapper;
    private final List<AgentChatModelPort> models;
    private final ModelProfileRepository profiles;
    private final RetrievalPort retrieval;
    private final AgentKnowledgeToolPort knowledgeTools;
    private final RerankModelPort rerank;
    private final PipelineConfigService pipelineConfigs;
    private final MetadataSchemaService metadataSchemas;
    private final AgentReactPersistencePort reactPersistence;
    private final ConversationMemoryPort memory;
    private final RunRecordPort runRecords;
    private final RunEventHub events;
    private final CitationValidationPort citationValidation;
    private final CitationPort citations;
    private final Clock clock;

    public ReactAgentEngine(
            ObjectMapper objectMapper,
            List<AgentChatModelPort> models,
            ModelProfileRepository profiles,
            RetrievalPort retrieval,
            AgentKnowledgeToolPort knowledgeTools,
            RerankModelPort rerank,
            PipelineConfigService pipelineConfigs,
            MetadataSchemaService metadataSchemas,
            AgentReactPersistencePort reactPersistence,
            ConversationMemoryPort memory,
            RunRecordPort runRecords,
            RunEventHub events,
            CitationValidationPort citationValidation,
            CitationPort citations,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.models = models == null ? List.of() : List.copyOf(models);
        this.profiles = profiles;
        this.retrieval = retrieval;
        this.knowledgeTools = knowledgeTools;
        this.rerank = rerank;
        this.pipelineConfigs = pipelineConfigs;
        this.metadataSchemas = metadataSchemas;
        this.reactPersistence = reactPersistence;
        this.memory = memory;
        this.runRecords = runRecords;
        this.events = events;
        this.citationValidation = citationValidation;
        this.citations = citations;
        this.clock = clock;
    }

    public String execute(UUID runId, UUID conversationId, UUID organizationId, UUID userId,
                          CreateRunRequest request, boolean generateAnswer) {
        var filters = metadataSchemas.validateFilters(
                organizationId, request.scope().knowledgeBaseIds(), request.filters());
        var config = pipelineConfigs.resolve(organizationId, request.modelProfileId());
        var profileId = request.modelProfileId() == null ? config.chatProfileId() : request.modelProfileId();
        var profile = profiles.findById(profileId)
                .orElseThrow(() -> new IllegalStateException("DEEP CHAT model Profile was not found"));
        if (!Boolean.TRUE.equals(profile.capabilities().get("toolCalling"))) {
            throw new IllegalStateException("DEEP requires a CHAT Profile with native toolCalling=true; run the profile probe first");
        }
        if (profile.provider() == ModelProvider.OLLAMA) {
            throw new IllegalStateException("DEEP is not supported for OLLAMA Profiles; FAST remains available");
        }
        boolean localQwenStrategy = LOCAL_QWEN_STRATEGY.equals(
                String.valueOf(profile.settings().getOrDefault("agentStrategy", "")));
        runRecords.applyAgentRuntime(runId, config, profileId);
        var model = selectModel(profile.provider());
        var scope = RetrievalScope.forUser(organizationId, userId, request.scope().knowledgeBaseIds(),
                request.scope().documentIds(), filters, clock.instant());
        var messages = new ArrayList<AgentChatModelPort.AgentChatMessage>();
        messages.add(AgentChatModelPort.AgentChatMessage.system(systemPrompt(scope, config, localQwenStrategy)));
        for (var previous : memory.recentMessages(conversationId, config.recentTurns())) {
            var separator = previous.indexOf(':');
            if (separator > 0) {
                var role = previous.substring(0, separator).strip().toLowerCase();
                var content = previous.substring(separator + 1).strip();
                if ("user".equals(role)) messages.add(AgentChatModelPort.AgentChatMessage.user(content));
                else if ("assistant".equals(role)) messages.add(AgentChatModelPort.AgentChatMessage.assistant(content, "", List.of()));
            }
        }
        messages.add(AgentChatModelPort.AgentChatMessage.user(request.query().strip()));

        var seenChunks = new LinkedHashSet<UUID>();
        var references = new LinkedHashMap<String, ReactKnowledgeReference>();
        var savedCheckpoint = reactPersistence.loadCheckpoint(runId).orElse(null);
        if (savedCheckpoint != null && !savedCheckpoint.messages().isEmpty()) {
            var restored = savedCheckpoint.messages().stream().map(this::deserializeMessage).toList();
            messages.clear();
            messages.addAll(restored);
            seenChunks.addAll(savedCheckpoint.seenChunkIds());
            reactPersistence.loadArtifacts(runId).ifPresent(artifacts -> artifacts.knowledgeReferences()
                    .forEach(reference -> references.put(reference.referenceKey(), reference)));
        }
        var budget = new Budget(config.maxIterations(), config.maxSearchCalls(), config.maxDeepReadCalls(),
                config.maxToolCallsPerRound());
        var finalText = new StringBuilder();
        int emptyStops = 0;
        String lastNonTerminal = null;
        boolean contextSummaryAdded = false;
        int contextCompressionCount = 0;
        int strategyNudges = 0;

        for (int iteration = 1; iteration <= budget.maxIterations; iteration++) {
            checkCancelled(runId);
            budget.iterations = iteration;
            int estimatedContextChars = messages.stream().mapToInt(message ->
                    message.content().length() + message.reasoningContent().length()
                            + message.toolCalls().stream().mapToInt(call -> call.arguments().length() + call.name().length()).sum())
                    .sum();
            int contextLimitChars = Math.max(4_096, config.maxContextTokens() * 4);
            if (!contextSummaryAdded && estimatedContextChars >= contextLimitChars / 2) {
                messages.add(1, AgentChatModelPort.AgentChatMessage.user(
                        "Earlier tool results are summarized; use fresh scoped tools for any missing evidence."));
                contextSummaryAdded = true;
                events.publish(runId, StreamEventType.CONTEXT_COMPRESSED, Map.of(
                        "kind", "summary", "estimatedChars", estimatedContextChars));
            }
            if (estimatedContextChars >= (long) contextLimitChars * 4 / 5) {
                int before = messages.size();
                compactContext(messages);
                if (messages.size() < before) {
                    contextCompressionCount++;
                    events.publish(runId, StreamEventType.CONTEXT_COMPRESSED, Map.of(
                            "kind", "hard", "estimatedChars", estimatedContextChars,
                            "remainingMessages", messages.size()));
                }
            }
            events.publish(runId, StreamEventType.REACT_ROUND_STARTED, Map.of(
                    "round", iteration, "maxIterations", budget.maxIterations, "searchCalls", budget.searchCalls,
                    "deepReadCalls", budget.deepReadCalls));
            var started = clock.instant();
            var deltas = new ArrayList<AgentChatModelPort.AgentChatDelta>();
            AgentChatModelPort.AgentChatResponse response;
            try {
                response = model.chat(profileId, new AgentChatModelPort.AgentChatRequest(
                        messages, tools(localQwenStrategy), AgentChatModelPort.ToolChoice.auto(), config.parallelToolCalls(),
                        config.temperature(), config.maxCompletionTokens(), config.llmTimeoutSeconds(), true),
                        delta -> {
                            deltas.add(delta);
                            if (generateAnswer && !delta.content().isBlank()) {
                                finalText.append(delta.content());
                                events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", delta.content()));
                            }
                        });
            } catch (RuntimeException failure) {
                // Retrieval-only runs must never perform a hidden final synthesis.  Once
                // evidence has been collected, a transient provider failure is a
                // completed retrieval outcome rather than a reason to make another LLM
                // request whose answer will be discarded.
                if (!generateAnswer && !references.isEmpty()) {
                    break;
                }
                if (iteration >= budget.maxIterations || !references.isEmpty()) {
                    response = model.chat(profileId, new AgentChatModelPort.AgentChatRequest(
                            messages, List.of(), AgentChatModelPort.ToolChoice.none(), false,
                            config.temperature(), config.maxCompletionTokens(), config.llmTimeoutSeconds(), true),
                            delta -> {
                                if (generateAnswer && !delta.content().isBlank()) {
                                    finalText.append(delta.content());
                                    events.publish(runId, StreamEventType.ANSWER_DELTA, Map.of("text", delta.content()));
                                }
                            });
                } else {
                    throw failure;
                }
            }
            var assistant = response.message();
            var stepId = UUID.randomUUID();
            var toolCalls = assistant.toolCalls();
            reactPersistence.saveStep(new ReactStep(stepId, runId, iteration,
                    ReactStepStatus.COMPLETED,
                    toolCalls.isEmpty() ? "自然停止" : "模型请求知识工具",
                    assistant.content(), response.finishReason(), response.providerMetadata(), usage(response.usage()),
                    started, clock.instant()));
            events.publish(runId, StreamEventType.AGENT_ACTION_UPDATED, Map.of(
                    "round", iteration, "action", toolCalls.isEmpty() ? "stop" : "tool_call",
                    "toolCount", toolCalls.size(), "finishReason", String.valueOf(response.finishReason())));

            if (toolCalls.isEmpty()) {
                if (localQwenStrategy && needsEvidenceFollowup(config, budget, references, strategyNudges)) {
                    strategyNudges++;
                    messages.add(AgentChatModelPort.AgentChatMessage.assistant(
                            assistant.content(), assistant.reasoningContent(), List.of()));
                    messages.add(AgentChatModelPort.AgentChatMessage.user(
                            followupInstruction(budget, references)));
                    events.publish(runId, StreamEventType.AGENT_ACTION_UPDATED, Map.of(
                            "round", iteration, "action", "evidence_followup",
                            "reason", budget.deepReadCalls == 0 ? "deep_read_required" : "search_required",
                            "attempt", strategyNudges));
                    continue;
                }
                if (response.finishReason() == null || !"stop".equalsIgnoreCase(response.finishReason())) {
                    if (++emptyStops <= 2) {
                        messages.add(AgentChatModelPort.AgentChatMessage.assistant(assistant.content(), assistant.reasoningContent(), List.of()));
                        messages.add(AgentChatModelPort.AgentChatMessage.user("请继续执行检索；若证据足够请自然停止并给出答案。"));
                        continue;
                    }
                }
                if (assistant.content().isBlank() && ++emptyStops <= 2) {
                    messages.add(AgentChatModelPort.AgentChatMessage.assistant("", assistant.reasoningContent(), List.of()));
                    messages.add(AgentChatModelPort.AgentChatMessage.user("请给出基于已返回工具结果的简洁答案。"));
                    continue;
                }
                break;
            }
            if (sameContent(lastNonTerminal, assistant.content())) {
                throw new IllegalStateException("DEEP model repeated the same non-terminal response twice");
            }
            lastNonTerminal = assistant.content();
            // Keep the complete assistant tool-call list in the protocol history.  If a
            // provider exceeds the per-round budget, append an error Tool Result for the
            // omitted calls instead of leaving unmatched tool calls in the transcript.
            messages.add(assistant);
            for (int index = 0; index < toolCalls.size(); index++) {
                checkCancelled(runId);
                var call = toolCalls.get(index);
                var callId = UUID.randomUUID();
                var callStarted = clock.instant();
                events.publish(runId, StreamEventType.TOOL_CALL_STARTED, Map.of(
                        "round", iteration, "toolCallId", call.id(), "tool", call.name()));
                ToolResult result;
                try {
                    if (index >= budget.maxToolCallsPerRound) {
                        throw new IllegalStateException("Tool-call budget per round exhausted");
                    }
                    var arguments = parseArguments(call.arguments());
                    reactPersistence.saveToolCall(new ReactToolCall(callId, runId, stepId, call.id(), index,
                            call.name(), arguments, ReactToolCallStatus.RUNNING, null, Map.of(), Map.of(),
                            null, null, callStarted, null));
                    result = executeTool(call.name(), arguments, scope, budget, seenChunks, references,
                            runId, callId, callStarted, config.rerankProfileId(), config.minimumRerankScore(),
                            localQwenStrategy);
                    reactPersistence.saveToolCall(new ReactToolCall(callId, runId, stepId, call.id(), index,
                            call.name(), arguments, ReactToolCallStatus.SUCCEEDED, result.output(), result.data(), Map.of(),
                            result.count(), elapsed(callStarted), callStarted, clock.instant()));
                    events.publish(runId, StreamEventType.TOOL_CALL_COMPLETED, Map.of(
                            "toolCallId", call.id(), "tool", call.name(), "resultCount", result.count(),
                            "latencyMs", elapsed(callStarted)));
                } catch (RuntimeException failure) {
                    var message = safeMessage(failure);
                    result = ToolResult.error(message);
                    reactPersistence.saveToolCall(new ReactToolCall(callId, runId, stepId, call.id(), index,
                            call.name(), parseArgumentsLenient(call.arguments()), ReactToolCallStatus.FAILED, result.output(),
                            Map.of(), Map.of("message", message), 0, elapsed(callStarted), callStarted, clock.instant()));
                    events.publish(runId, StreamEventType.TOOL_CALL_FAILED, Map.of(
                            "toolCallId", call.id(), "tool", call.name(), "message", message));
                }
                messages.add(AgentChatModelPort.AgentChatMessage.tool(call.id(), result.output()));
                saveCheckpoint(runId, iteration, messages, budget, seenChunks, references.keySet());
            }
        }
        if (generateAnswer) {
            var verified = verifyAndCite(runId, organizationId, userId, finalText.toString(), references);
            if (!verified.equals(finalText.toString())) {
                events.publish(runId, StreamEventType.ANSWER_REPLACED, Map.of("text", verified));
            }
            memory.append(conversationId, "user", request.query().strip(), runId);
            memory.append(conversationId, "assistant", verified, runId);
            return verified;
        }
        saveCheckpoint(runId, budget.iterations, messages, budget, seenChunks, references.keySet());
        return "";
    }

    private void compactContext(List<AgentChatModelPort.AgentChatMessage> messages) {
        if (messages.size() <= 8) return;
        int start = Math.max(1, messages.size() - 10);
        while (start > 1 && messages.get(start).role() == AgentChatModelPort.Role.TOOL) start--;
        var tail = new ArrayList<>(messages.subList(start, messages.size()));
        messages.subList(1, messages.size()).clear();
        messages.add(AgentChatModelPort.AgentChatMessage.user(
                "旧检索结果已省略，请重新检索；以下保留最近的完整对话组。"));
        messages.addAll(tail);
    }

    private AgentChatModelPort selectModel(ModelProvider provider) {
        return models.stream().filter(model -> model.supports(provider)).findFirst()
                .orElseGet(() -> {
                    if (models.size() == 1) return models.getFirst();
                    throw new IllegalStateException("No native AgentChat adapter for provider " + provider);
                });
    }

    private String systemPrompt(RetrievalScope scope, com.yanyue.rag.domain.model.PipelineConfig config,
                                boolean localQwenStrategy) {
        return "You are WeKnora Progressive RAG agent (" + PROMPT_VERSION + ").\n"
                + "Think, call a knowledge tool, observe the result, and repeat only when useful. "
                + "Never invent access: server scope is authoritative and tool arguments can only narrow it. "
                + "Use knowledge_search for semantic/keyword evidence, grep_chunks for exact text, "
                + "list_knowledge_chunks for deep reading, and get_document_info for document metadata. "
                + "Stop naturally when evidence is sufficient. "
                + (config.requireDeepReadBeforeAnswer() ? "Before answering, perform at least one deep read. " : "Deep read when it improves evidence. ")
                + "As guidance, use no more than " + config.maxRetrievalRounds() + " retrieval rounds and "
                + config.maxSubQueries() + " focused subqueries unless the evidence clearly requires less. "
                + "Citations may use <kb doc=\"DOCUMENT_ID\" chunk_id=\"CHUNK_ID\"/>. "
                + "Scope KBs=" + scope.knowledgeBaseIds() + ", documents=" + scope.documentIds()
                + (localQwenStrategy
                ? "\nLocal Qwen evidence policy: do not stop immediately after the first knowledge_search. "
                + "For a direct question, inspect the best returned chunk with list_knowledge_chunks before stopping. "
                + "For multi-intent or sparse questions, split the intents into focused queries and search again when a result is incomplete. "
                + "Use the returned chunk_id (preferred) or knowledge_id for deep reading; after observing the deep-read result, stop only when the requested evidence is covered."
                : "")
                + "\nGive a concise evidence-grounded answer when retrieval is complete.";
    }

    private List<AgentChatModelPort.ToolDefinition> tools(boolean localQwenStrategy) {
        var deepReadDescription = localQwenStrategy
                ? "Deep-read a document returned by knowledge_search. Set knowledge_id to the result's documentId (a document UUID, not the knowledge-base UUID); only set chunk_id when the exact result chunk_id is available. This is the required evidence read, not metadata lookup."
                : "Deep-read chunks for a document or chunk in the current scope.";
        return List.of(
                new AgentChatModelPort.ToolDefinition("knowledge_search", "Search current scoped published knowledge with hybrid retrieval.",
                        Map.of("type", "object", "properties", Map.of(
                                "queries", Map.of("type", "array", "minItems", 1, "maxItems", 5, "items", Map.of("type", "string")),
                                "knowledge_base_ids", Map.of("type", "array", "items", Map.of("type", "string"))),
                                "required", List.of("queries"), "additionalProperties", false)),
                new AgentChatModelPort.ToolDefinition("grep_chunks", "Find exact text in current scoped chunks.",
                        Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string", "minLength", 1, "maxLength", 256)),
                                "required", List.of("query"), "additionalProperties", false)),
                new AgentChatModelPort.ToolDefinition("list_knowledge_chunks", deepReadDescription,
                        Map.of("type", "object", "properties", Map.of(
                                "knowledge_id", Map.of("type", "string", "description",
                                        localQwenStrategy ? "documentId from the latest knowledge_search result" : "document or knowledge-base UUID"),
                                "chunk_id", Map.of("type", "string", "description",
                                        localQwenStrategy ? "exact chunkId from the latest search result; do not use the KB id" : "optional exact chunk UUID"),
                                "offset", Map.of("type", "integer", "minimum", 0), "limit", Map.of("type", "integer", "minimum", 1,
                                        "maximum", localQwenStrategy ? 5 : 50)),
                                "required", localQwenStrategy ? List.of("knowledge_id") : List.of(),
                                "additionalProperties", false)),
                new AgentChatModelPort.ToolDefinition("get_document_info", "Read metadata for current scoped documents.",
                        Map.of("type", "object", "properties", Map.of(
                                "knowledge_ids", Map.of("type", "array", "items", Map.of("type", "string"))),
                                "required", List.of("knowledge_ids"), "additionalProperties", false))
        );
    }

    private boolean needsEvidenceFollowup(com.yanyue.rag.domain.model.PipelineConfig config, Budget budget,
                                          Map<String, ReactKnowledgeReference> references, int nudges) {
        // The local strategy is deliberately bounded.  It only repairs the
        // observed one-search-and-stop pattern and never turns the ReAct loop
        // into a fixed state machine for other Profiles.
        if (nudges >= 2 || budget.searchCalls == 0) return false;
        boolean successfulDeepRead = references.values().stream().anyMatch(ReactKnowledgeReference::deepRead);
        if (config.requireDeepReadBeforeAnswer() && !successfulDeepRead && !references.isEmpty()) return true;
        return references.isEmpty() && budget.searchCalls < 2;
    }

    private String followupInstruction(Budget budget, Map<String, ReactKnowledgeReference> references) {
        boolean successfulDeepRead = references.values().stream().anyMatch(ReactKnowledgeReference::deepRead);
        if (!successfulDeepRead && !references.isEmpty()) {
            return "继续取证，不要结束本轮：从最近 knowledge_search 结果选择最相关的 documentId，放入 list_knowledge_chunks 的 knowledge_id（不要把知识库 ID 当作 chunk_id），完成一次 Deep Read，然后再判断证据是否足够。";
        }
        return "继续检索，不要直接结束：上一轮没有得到可用证据，请把问题拆成更具体的查询后再次调用 knowledge_search。";
    }

    private ToolResult executeTool(String name, Map<String, Object> arguments, RetrievalScope scope, Budget budget,
                                   Set<UUID> seenChunks, Map<String, ReactKnowledgeReference> references,
                                   UUID runId, UUID toolCallId, Instant started,
                                   UUID rerankProfileId, double minimumRerankScore, boolean localQwenStrategy) {
        var source = switch (name) {
            case "knowledge_search" -> KnowledgeReferenceSource.KNOWLEDGE_SEARCH;
            case "grep_chunks" -> KnowledgeReferenceSource.GREP_CHUNKS;
            case "list_knowledge_chunks" -> KnowledgeReferenceSource.LIST_KNOWLEDGE_CHUNKS;
            case "get_document_info" -> KnowledgeReferenceSource.GET_DOCUMENT_INFO;
            default -> throw new IllegalArgumentException("Unknown knowledge tool: " + name);
        };
        List<RetrievalHit> hits;
        List<AgentKnowledgeToolPort.DocumentInfo> documentInfos = List.of();
        var knowledgeBaseByDocument = new LinkedHashMap<UUID, UUID>();
        if (source == KnowledgeReferenceSource.KNOWLEDGE_SEARCH) {
            budget.search();
            var queries = strings(arguments.get("queries"));
            if (queries.isEmpty() || queries.size() > 5) throw new IllegalArgumentException("queries must contain 1..5 strings");
            var searchScope = narrowKnowledgeBaseScope(scope, strings(arguments.get("knowledge_base_ids")));
            var merged = new LinkedHashMap<UUID, RetrievalHit>();
            for (var query : queries) {
                var keyword = retrieval.keywordSearch(query, searchScope, 30);
                var semantic = retrieval.semanticSearch(query, searchScope, 30, 4);
                var fused = ReciprocalRankFusion.fuse(List.of(keyword, semantic), 40);
                var ranked = rerank(fused, query, rerankProfileId, minimumRerankScore);
                ranked.forEach(hit -> merged.merge(hit.chunkId(), hit, (left, right) -> right.score() > left.score() ? right : left));
            }
            hits = List.copyOf(merged.values()).subList(0, Math.min(20, merged.size()));
            var documentIds = hits.stream().map(RetrievalHit::documentId).filter(java.util.Objects::nonNull)
                    .distinct().toList();
            if (!documentIds.isEmpty()) {
                knowledgeTools.getDocumentInfo(documentIds, searchScope).forEach(info ->
                        knowledgeBaseByDocument.put(info.documentId(), info.knowledgeBaseId()));
            }
        } else if (source == KnowledgeReferenceSource.GREP_CHUNKS) {
            budget.search();
            var query = text(arguments.get("query"), 256);
            var chunks = knowledgeTools.grepChunks(query, scope, 20);
            chunks.forEach(chunk -> knowledgeBaseByDocument.put(
                    chunk.hit().documentId(), chunk.knowledgeBaseId()));
            hits = chunks.stream().map(AgentKnowledgeToolPort.KnowledgeChunk::hit).toList();
        } else if (source == KnowledgeReferenceSource.LIST_KNOWLEDGE_CHUNKS) {
            if (localQwenStrategy && budget.searchCalls == 0) {
                throw new IllegalArgumentException(
                        "Run knowledge_search first; list_knowledge_chunks must use a documentId returned by that search");
            }
            budget.deepRead();
            var knowledgeId = uuid(arguments.get("knowledge_id"));
            var chunkId = uuid(arguments.get("chunk_id"));
            if (localQwenStrategy) {
                // Qwen3 occasionally copies the visible knowledge_base_ids
                // scope value into knowledge_id/chunk_id.  Treat that as a
                // request to read the first document found by this Run, not
                // as permission to scan the whole KB.  The server-side Scope
                // remains authoritative and the fallback is local-profile
                // only.
                if (knowledgeId != null && scope.knowledgeBaseIds().contains(knowledgeId)) {
                    knowledgeId = firstDiscoveredDocument(references);
                }
                if (chunkId != null && scope.knowledgeBaseIds().contains(chunkId)) {
                    chunkId = null;
                    knowledgeId = firstDiscoveredDocument(references);
                }
            }
            var offset = integer(arguments.get("offset"), 0, 0, 50_000);
            var limit = integer(arguments.get("limit"), 20, 1, 50);
            if (localQwenStrategy) limit = Math.min(limit, 5);
            var chunks = knowledgeTools.listKnowledgeChunks(knowledgeId, chunkId, scope, offset, limit);
            chunks.forEach(chunk -> knowledgeBaseByDocument.put(
                    chunk.hit().documentId(), chunk.knowledgeBaseId()));
            hits = chunks.stream().map(AgentKnowledgeToolPort.KnowledgeChunk::hit).toList();
        } else {
            budget.deepRead();
            var ids = strings(arguments.get("knowledge_ids")).stream().map(this::uuid).toList();
            documentInfos = knowledgeTools.getDocumentInfo(ids, scope);
            hits = List.of();
        }
        var outputRows = new ArrayList<Map<String, Object>>();
        int discovery = references.size();
        for (var hit : hits) {
            if (hit.chunkId() == null) continue;
            boolean deep = source == KnowledgeReferenceSource.LIST_KNOWLEDGE_CHUNKS
                    || source == KnowledgeReferenceSource.GET_DOCUMENT_INFO;
            var key = "chunk:" + hit.chunkId();
            var existing = references.get(key);
            boolean alreadySeen = existing != null;
            Long nextDeepReadOrder = existing == null ? null : existing.firstDeepReadOrder();
            if (deep && nextDeepReadOrder == null) nextDeepReadOrder = Long.valueOf(discovery);
            var knowledgeBaseId = knowledgeBaseByDocument.get(hit.documentId());
            if (knowledgeBaseId == null && scope.knowledgeBaseIds().size() == 1) {
                knowledgeBaseId = scope.knowledgeBaseIds().getFirst();
            }
            if (knowledgeBaseId == null) {
                throw new IllegalStateException("Knowledge tool did not return the owning knowledge base");
            }
            var reference = existing == null ? new ReactKnowledgeReference(
                    UUID.randomUUID(), runId, toolCallId,
                    knowledgeBaseId,
                    hit.documentId(), hit.documentVersionId(), hit.chunkId(), hit.documentTitle(),
                    truncate(hit.text(), 4_000), hit.sourceStart(), hit.sourceEnd(), source, List.of(source), deep,
                    hit.score(), Map.of(), (long) discovery++, deep ? (long) discovery : null, clock.instant(), clock.instant())
                    : new ReactKnowledgeReference(existing.id(), existing.runId(), toolCallId,
                    existing.knowledgeBaseId(), existing.documentId(), existing.documentVersionId(), existing.chunkId(),
                    existing.documentTitle(), deep ? truncate(hit.text(), 4_000) : existing.excerpt(),
                    existing.sourceStart(), existing.sourceEnd(), existing.source(), mergeSources(existing.sources(), source),
                    existing.deepRead() || deep, Math.max(existing.score() == null ? 0 : existing.score(), hit.score()),
                    existing.metadata(), existing.firstDiscoveryOrder(), nextDeepReadOrder,
                    existing.createdAt(), clock.instant());
            references.put(key, reference);
            seenChunks.add(hit.chunkId());
            // Persist every observation so a later deep read upgrades the same
            // search reference without creating a duplicate projection row.
            reactPersistence.saveKnowledgeReference(reference);
            if (alreadySeen) {
                outputRows.add(Map.of("already_seen", true, "chunkId", hit.chunkId(),
                        "documentId", hit.documentId(), "documentVersionId", hit.documentVersionId()));
            } else {
                outputRows.add(Map.of("documentId", hit.documentId(), "documentVersionId", hit.documentVersionId(),
                        "chunkId", hit.chunkId(), "title", hit.documentTitle(), "text", truncate(hit.text(), 1_500),
                        "score", hit.score(), "deepRead", deep));
            }
        }
        for (var info : documentInfos) {
            var row = new LinkedHashMap<String, Object>();
            row.put("knowledgeBaseId", info.knowledgeBaseId());
            row.put("documentId", info.documentId());
            row.put("documentVersionId", info.documentVersionId());
            row.put("title", info.title());
            row.put("sourceName", info.sourceName());
            row.put("sourceType", String.valueOf(info.sourceType()));
            row.put("versionLabel", String.valueOf(info.versionLabel()));
            row.put("owner", String.valueOf(info.owner()));
            row.put("businessDomain", String.valueOf(info.businessDomain()));
            row.put("tags", info.tags());
            row.put("metadata", info.metadata());
            outputRows.add(Map.copyOf(row));
        }
        var data = Map.<String, Object>of("tool", name, "count", outputRows.size(), "results", outputRows);
        return new ToolResult(successJson(data), data, outputRows.size());
    }

    private UUID firstDiscoveredDocument(Map<String, ReactKnowledgeReference> references) {
        return references.values().stream()
                .filter(reference -> reference.documentId() != null)
                .sorted(java.util.Comparator.comparing(
                        reference -> reference.firstDiscoveryOrder() == null
                                ? Long.MAX_VALUE : reference.firstDiscoveryOrder()))
                .map(ReactKnowledgeReference::documentId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Deep read requires a documentId returned by knowledge_search"));
    }

    private RetrievalScope narrowKnowledgeBaseScope(RetrievalScope scope, List<String> requested) {
        if (requested == null || requested.isEmpty()) return scope;
        var ids = requested.stream().map(this::uuid).toList();
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("knowledge_base_ids must contain UUIDs");
        }
        if (!scope.knowledgeBaseIds().isEmpty()) {
            ids = ids.stream().filter(scope.knowledgeBaseIds()::contains).distinct().toList();
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("knowledge_base_ids cannot expand the server scope");
            }
        }
        return new RetrievalScope(scope.organizationId(), scope.userId(), scope.accessControlBypass(), ids,
                scope.documentIds(), scope.metadataFilters(), scope.effectiveAt());
    }

    private List<RetrievalHit> rerank(List<RetrievalHit> hits, String query, UUID profileId, double minimumScore) {
        if (hits.isEmpty()) return List.of();
        try {
            var scores = rerank.rerank(profileId, query, hits.stream().map(RetrievalHit::text).toList(),
                    Math.min(20, hits.size()));
            if (scores == null || scores.isEmpty()) return hits.stream().limit(20).toList();
            return scores.stream().filter(score -> score.index() >= 0 && score.index() < hits.size())
                    .filter(score -> score.score() >= minimumScore)
                    .sorted(java.util.Comparator.comparingDouble(
                            com.yanyue.rag.domain.port.RerankModelPort.RerankScore::score).reversed())
                    .limit(20)
                    .map(score -> hits.get(score.index()).withScore(score.score(),
                            appendSource(hits.get(score.index()).sources(), "rerank")))
                    .toList();
        } catch (RuntimeException ignored) {
            return hits.stream().limit(20).toList();
        }
    }

    private List<String> appendSource(List<String> sources, String source) {
        var values = new ArrayList<>(sources == null ? List.<String>of() : sources);
        if (!values.contains(source)) values.add(source);
        return List.copyOf(values);
    }

    private String verifyAndCite(UUID runId, UUID organizationId, UUID userId, String answer,
                                 Map<String, ReactKnowledgeReference> references) {
        if (answer == null || answer.isBlank()) return "";
        var result = new StringBuilder();
        var matcher = KB_TAG.matcher(answer);
        int last = 0;
        int index = 0;
        while (matcher.find()) {
            result.append(answer, last, matcher.start());
            var key = "chunk:" + matcher.group(2);
            var reference = references.get(key);
            RetrievalHit hit = reference == null ? null : new RetrievalHit(reference.chunkId(), null, reference.documentId(),
                    reference.documentVersionId(), reference.documentTitle(), reference.excerpt(),
                    reference.score() == null ? 0 : reference.score(), List.of(reference.source().name()), null,
                    reference.sourceStart(), reference.sourceEnd());
            if (hit != null && citationValidation.isCurrentlyValid(organizationId, userId, hit, clock.instant())) {
                index++;
                result.append('[').append(index).append(']');
                citations.save(runId, index, hit);
                events.publish(runId, StreamEventType.CITATION_VERIFIED, Map.of("index", index, "valid", true, "chunkId", hit.chunkId()));
                events.publish(runId, StreamEventType.CITATION, Map.of("index", index, "chunkId", hit.chunkId(),
                        "documentId", hit.documentId(), "documentVersionId", hit.documentVersionId(),
                        "documentTitle", hit.documentTitle(), "quote", hit.text()));
            } else {
                events.publish(runId, StreamEventType.CITATION_VERIFIED, Map.of("valid", false, "chunkId", matcher.group(2)));
            }
            last = matcher.end();
        }
        result.append(answer.substring(last));
        return result.toString();
    }

    private void saveCheckpoint(UUID runId, int step, List<AgentChatModelPort.AgentChatMessage> messages,
                                Budget budget, Set<UUID> seen, Collection<String> referenceKeys) {
        var serialized = messages.stream().map(this::serializeMessage).toList();
        reactPersistence.saveCheckpoint(new ReactCheckpoint(runId, ReactCheckpoint.CURRENT_VERSION,
                "REACT", step, serialized, budget.asMap(), seen, referenceKeys.stream()
                .map(key -> key.substring(key.indexOf(':') + 1)).filter(value -> value.length() == 36)
                .map(UUID::fromString).toList(), List.of(), Map.of("pipelineVersion", PIPELINE_VERSION), clock.instant()));
        events.publish(runId, StreamEventType.BUDGET_UPDATED, budget.asMap());
    }

    private Map<String, Object> serializeMessage(AgentChatModelPort.AgentChatMessage message) {
        var map = new LinkedHashMap<String, Object>();
        map.put("role", message.role().name().toLowerCase());
        map.put("content", message.content());
        if (!message.toolCalls().isEmpty()) map.put("toolCalls", message.toolCalls());
        if (message.toolCallId() != null) map.put("toolCallId", message.toolCallId());
        return map;
    }

    @SuppressWarnings("unchecked")
    private AgentChatModelPort.AgentChatMessage deserializeMessage(Map<String, Object> value) {
        var role = String.valueOf(value.getOrDefault("role", "user")).toUpperCase();
        var content = String.valueOf(value.getOrDefault("content", ""));
        if ("SYSTEM".equals(role)) return AgentChatModelPort.AgentChatMessage.system(content);
        if ("TOOL".equals(role)) return AgentChatModelPort.AgentChatMessage.tool(
                String.valueOf(value.get("toolCallId")), content);
        if ("ASSISTANT".equals(role)) {
            var calls = new ArrayList<AgentChatModelPort.ToolCall>();
            var raw = value.get("toolCalls");
            if (raw instanceof Collection<?> collection) {
                for (var item : collection) {
                    if (item instanceof Map<?, ?> map) {
                        var arguments = map.containsKey("arguments") ? map.get("arguments") : "{}";
                        calls.add(new AgentChatModelPort.ToolCall(String.valueOf(map.get("id")),
                                String.valueOf(map.get("name")), String.valueOf(arguments)));
                    }
                }
            }
            return AgentChatModelPort.AgentChatMessage.assistant(content, "", calls);
        }
        return AgentChatModelPort.AgentChatMessage.user(content);
    }

    private Map<String, Object> parseArguments(String arguments) {
        try {
            var node = objectMapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Tool arguments must be a JSON object");
            return objectMapper.convertValue(node, Map.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid tool arguments", exception);
        }
    }

    private Map<String, Object> parseArgumentsLenient(String arguments) {
        try { return parseArguments(arguments); } catch (RuntimeException ignored) { return Map.of(); }
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().filter(item -> item instanceof String && !((String) item).isBlank())
                .map(item -> ((String) item).strip()).toList();
    }

    private String text(Object value, int max) {
        if (!(value instanceof String string) || string.isBlank()) return null;
        if (string.length() > max) throw new IllegalArgumentException("Tool argument is too long");
        return string.strip();
    }

    private UUID uuid(Object value) {
        if (!(value instanceof String string) || string.isBlank()) return null;
        try {
            return UUID.fromString(string.strip());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Tool identifier must be a UUID", failure);
        }
    }

    private int integer(Object value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Tool integer argument is invalid");
        var converted = number.intValue();
        if (converted < minimum || converted > maximum) throw new IllegalArgumentException("Tool integer argument is out of range");
        return converted;
    }

    private String successJson(Map<String, Object> data) {
        try { return truncate(objectMapper.writeValueAsString(Map.of("success", true, "output", data, "data", data, "error", "")), MAX_OUTPUT_CHARS); }
        catch (JsonProcessingException exception) { return "{\"success\":false,\"error\":\"serialization failure\"}"; }
    }

    private void checkCancelled(UUID runId) {
        if (Thread.currentThread().isInterrupted() || runRecords.isCancellationRequested(runId)) {
            events.publish(runId, StreamEventType.RUN_CANCELLED, Map.of("reason", "cancelled-by-user"));
            throw new CancellationException("Agent Run was cancelled");
        }
    }

    private long elapsed(Instant started) { return Math.max(0, clock.instant().toEpochMilli() - started.toEpochMilli()); }
    private String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        int head = Math.max(1, (int) Math.floor(max * 0.70));
        int tail = Math.max(1, max - head - 32);
        return value.substring(0, head) + "\n...[output truncated; tail preserved]...\n"
                + value.substring(Math.max(head, value.length() - tail));
    }
    private String safeMessage(Throwable failure) { return truncate(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), 500); }
    private boolean sameContent(String first, String second) { return first != null && !first.isBlank() && first.equals(second); }
    private List<KnowledgeReferenceSource> mergeSources(List<KnowledgeReferenceSource> values, KnowledgeReferenceSource source) {
        var result = new ArrayList<>(values == null ? List.<KnowledgeReferenceSource>of() : values);
        if (!result.contains(source)) result.add(source);
        return List.copyOf(result);
    }
    private Map<String, Object> usage(AgentChatModelPort.TokenUsage usage) {
        if (usage == null) return Map.of();
        return Map.of("inputTokens", usage.inputTokens() == null ? 0 : usage.inputTokens(),
                "outputTokens", usage.outputTokens() == null ? 0 : usage.outputTokens(),
                "totalTokens", usage.totalTokens() == null ? 0 : usage.totalTokens());
    }

    private record ToolResult(String output, Map<String, Object> data, int count) {
        static ToolResult error(String message) {
            var data = Map.<String, Object>of("success", false, "error", message);
            try {
                var output = new ObjectMapper().writeValueAsString(
                        Map.of("success", false, "output", "", "data", data, "error", message));
                return new ToolResult(output, data, 0);
            } catch (JsonProcessingException ignored) {
                return new ToolResult("{\"success\":false,\"output\":\"\",\"data\":{},\"error\":\"tool failure\"}", data, 0);
            }
        }
    }

    private static final class Budget {
        final int maxIterations;
        final int maxSearchCalls;
        final int maxDeepReadCalls;
        final int maxToolCallsPerRound;
        int iterations;
        int searchCalls;
        int deepReadCalls;
        int toolCalls;
        Budget(int maxIterations, int maxSearchCalls, int maxDeepReadCalls, int maxToolCallsPerRound) {
            this.maxIterations = Math.max(1, maxIterations);
            this.maxSearchCalls = Math.max(1, maxSearchCalls);
            this.maxDeepReadCalls = Math.max(1, maxDeepReadCalls);
            this.maxToolCallsPerRound = Math.max(1, maxToolCallsPerRound);
        }
        void search() { if (++searchCalls > maxSearchCalls) throw new IllegalStateException("Search budget exhausted"); if (++toolCalls > maxIterations * maxToolCallsPerRound) throw new IllegalStateException("Tool budget exhausted"); }
        void deepRead() { if (++deepReadCalls > maxDeepReadCalls) throw new IllegalStateException("Deep-read budget exhausted"); if (++toolCalls > maxIterations * maxToolCallsPerRound) throw new IllegalStateException("Tool budget exhausted"); }
        Map<String, Object> asMap() { return Map.of("iterations", iterations, "searchCalls", searchCalls, "deepReadCalls", deepReadCalls, "toolCalls", toolCalls, "maxIterations", maxIterations, "maxSearchCalls", maxSearchCalls, "maxDeepReadCalls", maxDeepReadCalls, "maxToolCallsPerRound", maxToolCallsPerRound); }
    }
}
