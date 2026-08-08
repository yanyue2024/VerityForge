package com.yanyue.rag.infrastructure.model;

import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.port.AgentChatModelPort;
import com.yanyue.rag.domain.port.AgentChatModelPort.AgentChatDelta;
import com.yanyue.rag.domain.port.AgentChatModelPort.AgentChatMessage;
import com.yanyue.rag.domain.port.AgentChatModelPort.AgentChatRequest;
import com.yanyue.rag.domain.port.AgentChatModelPort.AgentChatResponse;
import com.yanyue.rag.domain.port.AgentChatModelPort.TokenUsage;
import com.yanyue.rag.domain.port.AgentChatModelPort.ToolCall;
import com.yanyue.rag.domain.port.AgentChatModelPort.ToolCallDelta;
import com.yanyue.rag.domain.port.CredentialCipher;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import com.yanyue.rag.domain.port.QueryRewriteModelPort;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import com.yanyue.rag.domain.port.StructuredReasoningModelPort;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleLanguageModelAdapter
        implements QueryRewriteModelPort, StructuredReasoningModelPort, StreamingAnswerModelPort, AgentChatModelPort {
    private static final int MAX_PRE_STREAM_RETRIES = 2;
    private static final int MAX_STRUCTURED_ATTEMPTS = 3;
    private static final int MAX_AGENT_RETRIES = 2;
    private static final int MAX_ERROR_BODY_LENGTH = 2_000;

    private final ModelProfileRepository profiles;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int structuredMaxTokens;
    private final String structuredReasoningEffort;
    private final String rewritePrompt;
    private final String answerPrompt;
    private final RagTelemetry telemetry;

    @Autowired
    public OpenAiCompatibleLanguageModelAdapter(
            ModelProfileRepository profiles,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            @Value("${rag.models.inference-connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${rag.models.inference-request-timeout-seconds:60}") long requestTimeoutSeconds,
            @Value("${rag.models.structured-max-tokens:1024}") int structuredMaxTokens,
            @Value("${rag.models.structured-reasoning-effort:}") String structuredReasoningEffort,
            @Value("${rag.models.proxy-url:}") String proxyUrl,
            @Value("${rag.models.no-proxy-hosts:}") String noProxyHosts,
            RagTelemetry telemetry
    ) {
        this.profiles = profiles;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
        this.httpClient = ModelHttpClientFactory.create(
                Duration.ofSeconds(connectTimeoutSeconds), proxyUrl, noProxyHosts);
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.structuredMaxTokens = Math.max(128, structuredMaxTokens);
        this.structuredReasoningEffort = supportedReasoningEffort(structuredReasoningEffort);
        this.rewritePrompt = resource("prompts/rewrite-v1.md");
        this.answerPrompt = resource("prompts/answer-v2.md");
        this.telemetry = telemetry;
    }

    OpenAiCompatibleLanguageModelAdapter(
            ModelProfileRepository profiles,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            long connectTimeoutSeconds,
            long requestTimeoutSeconds
    ) {
        this(profiles, credentialCipher, objectMapper, connectTimeoutSeconds, requestTimeoutSeconds, 1024, "",
                "", "", RagTelemetry.noop());
    }

    @Override
    public boolean supports(ModelProvider provider) {
        return provider == ModelProvider.OPENAI_COMPATIBLE;
    }

    @Override
    public RewriteResult rewrite(UUID profileId, String query, List<String> recentMessages) {
        var context = Map.of("recentMessages", recentMessages, "query", query);
        var userPrompt = json(context);
        String first = null;
        try {
            first = completeJson(profileId, "query-rewrite", rewritePrompt, userPrompt);
            return parseRewrite(first, query);
        } catch (RuntimeException firstFailure) {
            if (first == null) return RewriteResult.unchanged(query, safeReason(firstFailure));
            try {
                var repair = "The previous output was invalid. Return only a valid JSON object matching the required fields.\n"
                        + "Original input:\n" + userPrompt + "\nInvalid output or error:\n"
                        + truncate(firstFailure.getMessage(), 1000);
                return parseRewrite(completeJson(profileId, "query-rewrite-repair", rewritePrompt, repair), query);
            } catch (RuntimeException repairFailure) {
                return RewriteResult.unchanged(query, safeReason(repairFailure));
            }
        }
    }

    @Override
    public String completeJson(UUID profileId, String operation, String systemPrompt, String userPrompt) {
        return completeJson(profileId, operation, systemPrompt, userPrompt, requestTimeout);
    }

    @Override
    public String completeJson(
            UUID profileId,
            String operation,
            String systemPrompt,
            String userPrompt,
            Duration timeout
    ) {
        return completeJson(profileId, operation, systemPrompt, userPrompt, timeout, structuredMaxTokens);
    }

    @Override
    public String completeJson(
            UUID profileId,
            String operation,
            String systemPrompt,
            String userPrompt,
            Duration timeout,
            int maximumOutputTokens
    ) {
        return completeJson(profileId, operation, systemPrompt, userPrompt, timeout, maximumOutputTokens,
                MAX_STRUCTURED_ATTEMPTS);
    }

    @Override
    public String completeJson(
            UUID profileId,
            String operation,
            String systemPrompt,
            String userPrompt,
            Duration timeout,
            int maximumOutputTokens,
            int maximumPhysicalAttempts
    ) {
        return completeJson(profileId, operation, systemPrompt, userPrompt, timeout, maximumOutputTokens,
                maximumPhysicalAttempts, 0.0);
    }

    @Override
    public String completeJson(
            UUID profileId,
            String operation,
            String systemPrompt,
            String userPrompt,
            Duration timeout,
            int maximumOutputTokens,
            int maximumPhysicalAttempts,
            double temperature
    ) {
        var profile = requireProfile(profileId, ModelProfileType.QUERY_REWRITE, ModelProfileType.CHAT);
        var body = new LinkedHashMap<String, Object>();
        body.put("model", profile.modelName());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt
                        + "\nOutput must be one valid json object.")
        ));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("max_tokens", Math.max(1, Math.min(structuredMaxTokens, maximumOutputTokens)));
        body.put("temperature", Math.max(0.0, Math.min(2.0, temperature)));
        var reasoningEffort = profileReasoningEffort(profile);
        if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort);
        body.put("stream", false);
        return telemetry.observe("rag.model.request", modelTags(profile, operation), () -> {
            var response = postJsonWithRetry(profile, body, shorter(requestTimeout, timeout), operation,
                    Math.max(1, Math.min(MAX_STRUCTURED_ATTEMPTS, maximumPhysicalAttempts)));
            recordUsage(profile, operation, response.path("usage"));
            var content = response.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) throw new IllegalStateException(operation + " returned empty content");
            return content;
        });
    }

    private Duration shorter(Duration configured, Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            throw new IllegalArgumentException("模型调用剩余超时必须为正数");
        }
        return configured.compareTo(requested) <= 0 ? configured : requested;
    }

    @Override
    public GenerationResult generate(UUID profileId, AnswerRequest request, Consumer<String> onDelta) {
        return generate(profileId, request, onDelta, MAX_PRE_STREAM_RETRIES + 1);
    }

    @Override
    public GenerationResult generate(
            UUID profileId,
            AnswerRequest request,
            Consumer<String> onDelta,
            int maximumPhysicalAttempts
    ) {
        var profile = requireProfile(profileId, ModelProfileType.CHAT);
        if (profile.provider() != ModelProvider.OPENAI_COMPATIBLE) {
            throw new IllegalStateException("Streaming answer generation currently requires an OpenAI-compatible Profile");
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", profile.modelName());
        body.put("messages", List.of(
                Map.of("role", "system", "content",
                        request.systemInstruction() == null || request.systemInstruction().isBlank()
                                ? answerPrompt : request.systemInstruction()),
                Map.of("role", "user", "content", answerUserPrompt(request))
        ));
        body.put("max_tokens", request.maximumOutputTokens());
        if (request.temperature() != null) body.put("temperature", request.temperature());
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        var timeout = Duration.ofSeconds(Math.max(1, request.timeoutSeconds()));
        return telemetry.observe("rag.model.request", modelTags(profile, "answer"), () -> {
            var result = streamOpenAi(profile, body, timeout, onDelta,
                    Math.max(1, Math.min(MAX_PRE_STREAM_RETRIES + 1, maximumPhysicalAttempts)));
            telemetry.recordModelUsage(profile.provider().name(), profile.modelName(), "answer",
                    result.inputTokens(), result.outputTokens(), profile.settings());
            return result;
        });
    }

    @Override
    public AgentChatResponse chat(
            UUID profileId,
            AgentChatRequest request,
            Consumer<AgentChatDelta> onDelta
    ) {
        var profile = requireProfile(profileId, ModelProfileType.CHAT);
        if (profile.provider() != ModelProvider.OPENAI_COMPATIBLE) {
            throw new IllegalStateException("Agent tool calling requires an OpenAI-compatible CHAT Profile");
        }
        var body = agentRequestBody(profile, request);
        var timeout = Duration.ofSeconds(Math.max(5, request.timeoutSeconds()));
        var deltaConsumer = onDelta == null ? (Consumer<AgentChatDelta>) ignored -> { } : onDelta;
        return telemetry.observe("rag.model.request", modelTags(profile, "agent-chat"), () -> {
            var result = request.stream()
                    ? streamAgentOpenAi(profile, body, timeout, deltaConsumer)
                    : parseAgentResponse(postAgentJsonWithRetry(profile, body, timeout));
            var usage = result.usage();
            telemetry.recordModelUsage(profile.provider().name(), profile.modelName(), "agent-chat",
                    usage == null ? null : usage.inputTokens(),
                    usage == null ? null : usage.outputTokens(), profile.settings());
            return result;
        });
    }

    private Map<String, String> modelTags(ModelProfile profile, String operation) {
        return Map.of("provider", profile.provider().name(), "model", profile.modelName(),
                "operation", operation);
    }

    private String profileReasoningEffort(ModelProfile profile) {
        var configured = profile.settings().get("reasoningEffort");
        if (configured == null) configured = profile.settings().get("reasoning_effort");
        var profileValue = supportedReasoningEffort(configured == null ? null : configured.toString());
        return profileValue == null ? structuredReasoningEffort : profileValue;
    }

    private String supportedReasoningEffort(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        return Set.of("minimal", "low", "medium", "high").contains(normalized) ? normalized : null;
    }

    private void recordUsage(ModelProfile profile, String operation, JsonNode usage) {
        var input = usage.path("prompt_tokens").canConvertToInt() ? usage.path("prompt_tokens").asInt() : null;
        var output = usage.path("completion_tokens").canConvertToInt()
                ? usage.path("completion_tokens").asInt() : null;
        telemetry.recordModelUsage(profile.provider().name(), profile.modelName(), operation,
                input, output, profile.settings());
    }

    private Map<String, Object> agentRequestBody(ModelProfile profile, AgentChatRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", profile.modelName());
        body.put("messages", request.messages().stream().map(this::agentMessage).toList());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(tool -> Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.parameters()
                    )
            )).toList());
            if (request.toolChoice() != null) {
                body.put("tool_choice", toolChoice(request.toolChoice()));
            }
            if (request.parallelToolCalls() != null) {
                body.put("parallel_tool_calls", request.parallelToolCalls());
            }
        }
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxCompletionTokens() != null) {
            body.put("max_completion_tokens", request.maxCompletionTokens());
        }
        body.put("stream", request.stream());
        if (request.stream()) body.put("stream_options", Map.of("include_usage", true));
        return body;
    }

    private Map<String, Object> agentMessage(AgentChatMessage message) {
        var value = new LinkedHashMap<String, Object>();
        value.putAll(message.providerMetadata());
        value.put("role", message.role().name().toLowerCase(java.util.Locale.ROOT));
        if (message.role() == AgentChatModelPort.Role.ASSISTANT) {
            value.put("content", message.content().isEmpty() ? null : message.content());
            if (!message.reasoningContent().isEmpty()) {
                value.put("reasoning_content", message.reasoningContent());
            }
            if (!message.toolCalls().isEmpty()) {
                value.put("tool_calls", message.toolCalls().stream().map(this::agentToolCall).toList());
            }
        } else {
            value.put("content", message.content());
            if (message.role() == AgentChatModelPort.Role.TOOL) {
                value.put("tool_call_id", message.toolCallId());
            }
        }
        return value;
    }

    private Map<String, Object> agentToolCall(ToolCall call) {
        var value = new LinkedHashMap<String, Object>();
        value.putAll(call.providerMetadata());
        value.put("id", call.id());
        value.put("type", "function");
        value.put("function", Map.of("name", call.name(), "arguments", call.arguments()));
        return Map.copyOf(value);
    }

    private Object toolChoice(AgentChatModelPort.ToolChoice choice) {
        return switch (choice.mode()) {
            case AUTO -> "auto";
            case NONE -> "none";
            case REQUIRED -> "required";
            case FUNCTION -> Map.of(
                    "type", "function",
                    "function", Map.of("name", choice.functionName())
            );
        };
    }

    private AgentChatResponse parseAgentResponse(JsonNode response) {
        var choice = response.path("choices").path(0);
        var message = choice.path("message");
        if (!message.isObject()) throw new IllegalStateException("Agent model returned no assistant message");
        var assistant = new AgentChatMessage(
                AgentChatModelPort.Role.ASSISTANT,
                text(message.path("content")),
                reasoningText(message),
                parseToolCalls(message.path("tool_calls")),
                null,
                metadata(message, Set.of("role", "content", "reasoning", "reasoning_content", "tool_calls"))
        );
        if (assistant.content().isEmpty() && assistant.reasoningContent().isEmpty()
                && assistant.toolCalls().isEmpty()) {
            throw new IllegalStateException("Agent model returned an empty assistant message");
        }
        return new AgentChatResponse(
                assistant,
                textOrNull(choice.path("finish_reason")),
                usage(response.path("usage")),
                responseMetadata(response, choice)
        );
    }

    private List<ToolCall> parseToolCalls(JsonNode values) {
        if (values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray()) throw new IllegalStateException("Agent model returned invalid tool_calls");
        var calls = new ArrayList<ToolCall>();
        values.forEach(value -> {
            var type = value.path("type").asText("function");
            if (!"function".equals(type)) {
                throw new IllegalStateException("Agent model returned an unsupported tool call type: " + type);
            }
            var function = value.path("function");
            calls.add(new ToolCall(
                    value.path("id").asText(),
                    function.path("name").asText(),
                    function.path("arguments").asText(""),
                    metadata(value, Set.of("id", "type", "function"))
            ));
        });
        return List.copyOf(calls);
    }

    private AgentChatResponse streamAgentOpenAi(
            ModelProfile profile,
            Object body,
            Duration timeout,
            Consumer<AgentChatDelta> onDelta
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_AGENT_RETRIES; attempt++) {
            try {
                var response = httpClient.send(request(profile, body, timeout), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    close(response.body());
                    lastFailure = new IllegalStateException("Agent model returned HTTP " + response.statusCode());
                    if (attempt < MAX_AGENT_RETRIES) {
                        backoff(attempt);
                        continue;
                    }
                    throw lastFailure;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    var error = readLimited(response.body(), 2_000);
                    throw new IllegalStateException("Agent model returned HTTP " + response.statusCode()
                            + (error.isBlank() ? "" : ": " + error));
                }
                try {
                    return consumeAgentSse(response.body(), onDelta);
                } catch (IOException exception) {
                    throw new IllegalStateException("Agent model stream failed", exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Agent model request was interrupted", exception);
            } catch (IOException exception) {
                lastFailure = new IllegalStateException("Agent model request failed", exception);
                if (attempt < MAX_AGENT_RETRIES) {
                    try {
                        backoff(attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Agent model request was interrupted", interrupted);
                    }
                    continue;
                }
                throw lastFailure;
            }
        }
        throw lastFailure == null ? new IllegalStateException("Agent model request failed") : lastFailure;
    }

    private AgentChatResponse consumeAgentSse(InputStream input, Consumer<AgentChatDelta> onDelta) throws IOException {
        var content = new StringBuilder();
        var reasoning = new StringBuilder();
        var toolCalls = new TreeMap<Integer, ToolCallAccumulator>();
        var responseMetadata = new LinkedHashMap<String, Object>();
        var messageMetadata = new LinkedHashMap<String, Object>();
        TokenUsage usage = null;
        String finishReason = null;
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Agent stream was cancelled");
                if (!line.startsWith("data:")) continue;
                var data = line.substring(5).strip();
                if (data.isBlank() || "[DONE]".equals(data)) continue;
                JsonNode event;
                try {
                    event = objectMapper.readTree(data);
                } catch (JsonProcessingException exception) {
                    throw new IOException("Agent stream returned invalid JSON", exception);
                }
                responseMetadata.putAll(metadata(event, Set.of("choices", "usage")));
                var choice = event.path("choices").path(0);
                var delta = choice.path("delta");
                var contentFragment = text(delta.path("content"));
                var reasoningFragment = reasoningText(delta);
                content.append(contentFragment);
                reasoning.append(reasoningFragment);
                messageMetadata.putAll(metadata(delta,
                        Set.of("role", "content", "reasoning", "reasoning_content", "tool_calls")));
                var callDeltas = parseToolCallDeltas(delta.path("tool_calls"), toolCalls);
                var finish = textOrNull(choice.path("finish_reason"));
                if (finish != null) finishReason = finish;
                var eventUsage = usage(event.path("usage"));
                if (eventUsage != null) usage = eventUsage;
                if (!contentFragment.isEmpty() || !reasoningFragment.isEmpty() || !callDeltas.isEmpty()
                        || finish != null || eventUsage != null) {
                    onDelta.accept(new AgentChatDelta(contentFragment, reasoningFragment, callDeltas,
                            finish, eventUsage, metadata(event, Set.of("choices", "usage"))));
                }
            }
        }
        var calls = toolCalls.values().stream().map(ToolCallAccumulator::build).toList();
        if (content.isEmpty() && reasoning.isEmpty() && calls.isEmpty()) {
            throw new IllegalStateException("Agent model returned an empty stream");
        }
        var message = new AgentChatMessage(AgentChatModelPort.Role.ASSISTANT, content.toString(),
                reasoning.toString(), calls, null, messageMetadata);
        return new AgentChatResponse(message, finishReason, usage, responseMetadata);
    }

    private List<ToolCallDelta> parseToolCallDeltas(
            JsonNode values,
            Map<Integer, ToolCallAccumulator> accumulators
    ) {
        if (values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray()) throw new IllegalStateException("Agent stream returned invalid tool_calls");
        var deltas = new ArrayList<ToolCallDelta>();
        values.forEach(value -> {
            var indexNode = value.path("index");
            if (!indexNode.canConvertToInt() || indexNode.asInt() < 0) {
                throw new IllegalStateException("Agent stream returned a tool call without a valid index");
            }
            var index = indexNode.asInt();
            var type = value.path("type").asText("function");
            if (!"function".equals(type)) {
                throw new IllegalStateException("Agent model returned an unsupported tool call type: " + type);
            }
            var id = text(value.path("id"));
            var name = text(value.path("function").path("name"));
            var arguments = text(value.path("function").path("arguments"));
            var callMetadata = metadata(value, Set.of("index", "id", "type", "function"));
            accumulators.computeIfAbsent(index, ToolCallAccumulator::new)
                    .append(id, name, arguments, callMetadata);
            deltas.add(new ToolCallDelta(index, id, name, arguments, callMetadata));
        });
        return List.copyOf(deltas);
    }

    private JsonNode postAgentJsonWithRetry(ModelProfile profile, Object body, Duration timeout) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_AGENT_RETRIES; attempt++) {
            try {
                var response = httpClient.send(request(profile, body, timeout), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return objectMapper.readTree(response.body());
                }
                lastFailure = new IllegalStateException("Agent model returned HTTP " + response.statusCode());
                if ((response.statusCode() == 429 || response.statusCode() >= 500)
                        && attempt < MAX_AGENT_RETRIES) {
                    backoff(attempt);
                    continue;
                }
                throw lastFailure;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Agent model request was interrupted", exception);
            } catch (IOException exception) {
                lastFailure = new IllegalStateException("Agent model request failed", exception);
                if (attempt < MAX_AGENT_RETRIES) {
                    try {
                        backoff(attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Agent model request was interrupted", interrupted);
                    }
                    continue;
                }
                throw lastFailure;
            }
        }
        throw lastFailure == null ? new IllegalStateException("Agent model request failed") : lastFailure;
    }

    private TokenUsage usage(JsonNode value) {
        if (!value.isObject()) return null;
        return new TokenUsage(
                integerOrNull(value.path("prompt_tokens")),
                integerOrNull(value.path("completion_tokens")),
                integerOrNull(value.path("total_tokens")),
                metadata(value, Set.of("prompt_tokens", "completion_tokens", "total_tokens"))
        );
    }

    private Integer integerOrNull(JsonNode value) {
        return value.canConvertToInt() ? value.asInt() : null;
    }

    private String reasoningText(JsonNode message) {
        var nativeReasoning = text(message.path("reasoning_content"));
        return nativeReasoning.isEmpty() ? text(message.path("reasoning")) : nativeReasoning;
    }

    private String text(JsonNode value) {
        return value.isTextual() ? value.asText() : "";
    }

    private String textOrNull(JsonNode value) {
        var result = text(value);
        return result.isBlank() ? null : result;
    }

    private Map<String, Object> responseMetadata(JsonNode response, JsonNode choice) {
        var metadata = new LinkedHashMap<>(metadata(response, Set.of("choices", "usage")));
        var choiceMetadata = metadata(choice, Set.of("message", "finish_reason"));
        if (!choiceMetadata.isEmpty()) metadata.put("choice", choiceMetadata);
        return Map.copyOf(metadata);
    }

    private Map<String, Object> metadata(JsonNode value, Set<String> excluded) {
        if (!value.isObject()) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        value.fields().forEachRemaining(field -> {
            if (!excluded.contains(field.getKey()) && !field.getValue().isNull()) {
                result.put(field.getKey(), objectMapper.convertValue(field.getValue(), Object.class));
            }
        });
        return Map.copyOf(result);
    }

    private static final class ToolCallAccumulator {
        private final int index;
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private ToolCallAccumulator(int index) {
            this.index = index;
        }

        private void append(String idFragment, String nameFragment, String argumentsFragment,
                            Map<String, Object> providerMetadata) {
            id.append(idFragment);
            name.append(nameFragment);
            arguments.append(argumentsFragment);
            metadata.putAll(providerMetadata);
        }

        private ToolCall build() {
            try {
                return new ToolCall(id.toString(), name.toString(), arguments.toString(), metadata);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Agent stream returned an incomplete tool call at index " + index,
                        exception);
            }
        }
    }

    private RewriteResult parseRewrite(String content, String originalQuery) {
        try {
            var root = objectMapper.readTree(stripCodeFence(content));
            if (!root.isObject() || !root.has("rewriteNeeded") || !root.path("rewriteNeeded").isBoolean()) {
                throw new IllegalStateException("rewriteNeeded must be a boolean");
            }
            var standalone = root.path("standaloneQuery").asText().strip();
            if (standalone.isBlank() || standalone.length() > 20_000) {
                throw new IllegalStateException("standaloneQuery is blank or too long");
            }
            var references = new ArrayList<String>();
            var values = root.path("resolvedReferences");
            if (!values.isArray()) throw new IllegalStateException("resolvedReferences must be an array");
            values.forEach(value -> {
                if (!value.isTextual()) throw new IllegalStateException("resolvedReferences must contain strings");
                if (!value.asText().isBlank()) references.add(value.asText().strip());
            });
            var needed = root.path("rewriteNeeded").asBoolean();
            return new RewriteResult(needed, needed ? standalone : originalQuery, references, null);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Query rewrite returned invalid JSON", exception);
        }
    }

    private GenerationResult streamOpenAi(
            ModelProfile profile,
            Object body,
            Duration timeout,
            Consumer<String> onDelta,
            int maximumPhysicalAttempts
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < maximumPhysicalAttempts; attempt++) {
            var request = request(profile, body, timeout);
            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    close(response.body());
                    lastFailure = new IllegalStateException("Answer model returned HTTP " + response.statusCode());
                    if (attempt + 1 < maximumPhysicalAttempts) {
                        backoff(attempt);
                        continue;
                    }
                    throw lastFailure;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    var error = readLimited(response.body(), 2000);
                    throw new IllegalStateException("Answer model returned HTTP " + response.statusCode()
                            + (error.isBlank() ? "" : ": " + error));
                }
                return consumeSse(response.body(), onDelta);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Answer generation was interrupted", exception);
            } catch (IOException exception) {
                throw new IllegalStateException("Answer stream failed", exception);
            }
        }
        throw lastFailure == null ? new IllegalStateException("Answer generation failed") : lastFailure;
    }

    private GenerationResult consumeSse(InputStream input, Consumer<String> onDelta) throws IOException {
        var content = new StringBuilder();
        Integer inputTokens = null;
        Integer outputTokens = null;
        String finishReason = null;
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Answer stream was cancelled");
                if (!line.startsWith("data:")) continue;
                var data = line.substring(5).strip();
                if (data.isBlank() || "[DONE]".equals(data)) continue;
                JsonNode event;
                try {
                    event = objectMapper.readTree(data);
                } catch (JsonProcessingException exception) {
                    throw new IOException("Answer stream returned invalid JSON", exception);
                }
                var delta = event.path("choices").path(0).path("delta").path("content");
                if (delta.isTextual() && !delta.asText().isEmpty()) {
                    content.append(delta.asText());
                    onDelta.accept(delta.asText());
                }
                var finish = event.path("choices").path(0).path("finish_reason");
                if (finish.isTextual()) finishReason = finish.asText();
                var usage = event.path("usage");
                if (usage.isObject()) {
                    if (usage.path("prompt_tokens").canConvertToInt()) inputTokens = usage.path("prompt_tokens").asInt();
                    if (usage.path("completion_tokens").canConvertToInt()) outputTokens = usage.path("completion_tokens").asInt();
                }
            }
        }
        if (content.isEmpty()) throw new IllegalStateException("Answer model returned an empty stream");
        return new GenerationResult(content.toString(), inputTokens, outputTokens, finishReason);
    }

    private JsonNode postJsonWithRetry(
            ModelProfile profile,
            Object body,
            Duration timeout,
            String operation,
            int maximumPhysicalAttempts
    ) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < maximumPhysicalAttempts; attempt++) {
            var remaining = remainingTimeout(deadlineNanos);
            if (remaining.isZero()) break;
            try {
                var response = httpClient.send(
                        request(profile, body, remaining), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    try {
                        return objectMapper.readTree(response.body());
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException(operation + " returned invalid JSON", exception);
                    }
                }
                lastFailure = structuredHttpFailure(operation, response.statusCode(), response.body());
                if ((response.statusCode() == 429 || response.statusCode() >= 500)
                        && attempt + 1 < maximumPhysicalAttempts
                        && structuredBackoff(response, attempt, deadlineNanos)) {
                    continue;
                }
                throw lastFailure;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(operation + " was interrupted", exception);
            } catch (IOException exception) {
                lastFailure = new IllegalStateException(operation + " request failed", exception);
                if (!(exception instanceof HttpTimeoutException) && attempt + 1 < maximumPhysicalAttempts) {
                    try {
                        if (structuredBackoff(null, attempt, deadlineNanos)) continue;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(operation + " was interrupted", interrupted);
                    }
                }
                throw lastFailure;
            }
        }
        throw lastFailure == null ? new IllegalStateException(operation + " failed") : lastFailure;
    }

    private Duration remainingTimeout(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos <= 0 ? Duration.ZERO : Duration.ofNanos(remainingNanos);
    }

    private IllegalStateException structuredHttpFailure(String operation, int statusCode, String responseBody) {
        var detail = responseBody == null ? "" : responseBody.strip();
        var suffix = detail.isEmpty() ? "" : ": " + truncate(detail, MAX_ERROR_BODY_LENGTH);
        return new IllegalStateException(operation + " returned HTTP " + statusCode + suffix);
    }

    private HttpRequest request(ModelProfile profile, Object body, Duration timeout) {
        var endpoint = profile.provider() == ModelProvider.OLLAMA
                ? profile.baseUrl() + "/api/chat"
                : profile.baseUrl() + "/chat/completions";
        var builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)));
        if (profile.provider() == ModelProvider.OPENAI_COMPATIBLE) {
            if (!profile.hasApiKey()) throw new IllegalStateException("Model Profile has no API key");
            builder.header("Authorization", "Bearer " + credentialCipher.decrypt(profile.encryptedApiKey()));
        }
        return builder.build();
    }

    private ModelProfile requireProfile(UUID profileId, ModelProfileType... expectedTypes) {
        var profile = profiles.findById(profileId)
                .orElseThrow(() -> new IllegalStateException("Model Profile was not found"));
        if (!profile.enabled() || java.util.Arrays.stream(expectedTypes).noneMatch(type -> type == profile.profileType())) {
            throw new IllegalStateException("Model Profile is disabled or has the wrong capability");
        }
        if (profile.provider() != ModelProvider.OPENAI_COMPATIBLE && profile.provider() != ModelProvider.OLLAMA) {
            throw new IllegalStateException("Profile provider does not support language model inference");
        }
        return profile;
    }

    private String answerUserPrompt(AnswerRequest request) {
        var evidence = request.evidence().stream().map(item -> Map.<String, Object>of(
                "id", item.evidenceId(),
                "documentTitle", item.documentTitle(),
                "documentVersionId", item.documentVersionId(),
                "chunkId", item.chunkId(),
                "text", item.text()
        )).toList();
        var value = new LinkedHashMap<String, Object>();
        value.put("question", request.question());
        value.put("standaloneQuery", request.standaloneQuery());
        value.put("conversationHistory", request.conversationHistory());
        value.put("personalizationMemory", request.personalizationMemory());
        value.put("evidence", evidence);
        return json(value);
    }

    private String resource(String path) {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Prompt resource was not found: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read prompt resource: " + path, exception);
        }
    }

    private String stripCodeFence(String value) {
        var stripped = value.strip();
        if (!stripped.startsWith("```")) return stripped;
        var firstNewline = stripped.indexOf('\n');
        var lastFence = stripped.lastIndexOf("```");
        return firstNewline >= 0 && lastFence > firstNewline
                ? stripped.substring(firstNewline + 1, lastFence).strip()
                : stripped;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize model request", exception);
        }
    }

    private String safeReason(Throwable failure) {
        var message = failure.getMessage();
        return truncate(message == null ? failure.getClass().getSimpleName() : message, 500);
    }

    private String truncate(String value, int maximum) {
        if (value == null) return "unknown";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private void backoff(int attempt) throws InterruptedException {
        Thread.sleep(250L * (attempt + 1));
    }

    private boolean structuredBackoff(
            HttpResponse<?> response,
            int attempt,
            long deadlineNanos
    ) throws InterruptedException {
        long exponentialMs = 1_000L << Math.min(attempt, 4);
        long retryAfterMs = response == null ? 0L : response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        return java.util.Optional.of(Math.multiplyExact(Long.parseLong(value.strip()), 1_000L));
                    } catch (ArithmeticException | NumberFormatException ignored) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(0L);
        long jitterMs = ThreadLocalRandom.current().nextLong(250L, 1_001L);
        long baseMs = Math.min(29_000L, Math.max(exponentialMs, retryAfterMs));
        long delayMs = baseMs + jitterMs;
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= Duration.ofMillis(delayMs).toNanos()) return false;
        Thread.sleep(delayMs);
        return deadlineNanos - System.nanoTime() > 0;
    }

    private void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The response body is discarded before a retry.
        }
    }

    private String readLimited(InputStream input, int maximumBytes) throws IOException {
        try (input) {
            return new String(input.readNBytes(maximumBytes), StandardCharsets.UTF_8).strip();
        }
    }
}
