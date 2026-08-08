package com.yanyue.rag.infrastructure.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.port.ModelProfileProbePort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JdkModelProfileProbeAdapter implements ModelProfileProbePort {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    @Autowired
    public JdkModelProfileProbeAdapter(
            ObjectMapper objectMapper,
            @Value("${rag.models.probe-connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${rag.models.probe-request-timeout-seconds:30}") long requestTimeoutSeconds,
            @Value("${rag.models.proxy-url:}") String proxyUrl,
            @Value("${rag.models.no-proxy-hosts:}") String noProxyHosts
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = ModelHttpClientFactory.create(
                Duration.ofSeconds(connectTimeoutSeconds), proxyUrl, noProxyHosts);
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
    }

    JdkModelProfileProbeAdapter(ObjectMapper objectMapper, long connectTimeoutSeconds, long requestTimeoutSeconds) {
        this(objectMapper, connectTimeoutSeconds, requestTimeoutSeconds, "", "");
    }

    @Override
    public ProbeResult probe(ProbeTarget target) {
        if (target.provider() == ModelProvider.DEMO) {
            return new ProbeResult(0, "Demo profile is available",
                    Map.of("mode", "demo", "toolCalling", true));
        }
        var started = System.nanoTime();
        var response = switch (target.provider()) {
            case OPENAI_COMPATIBLE -> probeOpenAi(target);
            case OLLAMA -> probeOllama(target);
            case LOCAL_BGE -> probeLocalBge(target);
            case DEMO -> throw new IllegalStateException("Unexpected demo provider");
        };
        var latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new ProbeResult(latencyMs, "Model endpoint responded successfully", response);
    }

    private Map<String, Object> probeOpenAi(ProbeTarget target) {
        return switch (target.profileType()) {
            case CHAT -> probeOpenAiToolCalling(target);
            case QUERY_REWRITE -> {
                var body = Map.of(
                        "model", target.modelName(),
                        "messages", List.of(Map.of("role", "user", "content", "Reply with OK.")),
                        "max_tokens", 8,
                        "temperature", 0
                );
                var json = post(target.baseUrl() + "/chat/completions", body, target.apiKey());
                var returnedModel = json.path("model").asText(target.modelName());
                yield Map.of("protocol", "openai-chat-completions", "model", returnedModel,
                        "toolCalling", false);
            }
            case EMBEDDING -> {
                var json = post(target.baseUrl() + "/embeddings",
                        Map.of("model", target.modelName(), "input", List.of("健康检查")), target.apiKey());
                var embedding = json.path("data").path(0).path("embedding");
                if (!embedding.isArray() || embedding.isEmpty()) {
                    throw new IllegalStateException("Embedding endpoint returned no vector");
                }
                yield Map.of("protocol", "openai-embeddings", "model", target.modelName(),
                        "dimension", embedding.size());
            }
            case RERANK -> rerankProbe(target);
        };
    }

    private Map<String, Object> probeOpenAiToolCalling(ProbeTarget target) {
        var toolName = "profile_health_check";
        var firstBody = new LinkedHashMap<String, Object>();
        firstBody.put("model", target.modelName());
        firstBody.put("messages", List.of(Map.of(
                "role", "user",
                "content", "Call profile_health_check with nonce 'probe'. Do not answer directly."
        )));
        firstBody.put("tools", List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", toolName,
                        "description", "Checks native tool calling support for this model profile.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of("nonce", Map.of("type", "string")),
                                "required", List.of("nonce"),
                                "additionalProperties", false
                        )
                )
        )));
        // The probe exposes exactly one tool, so "required" preserves the
        // forced-call semantics and works with gateways that reject the
        // object form of tool_choice.
        firstBody.put("tool_choice", "required");
        firstBody.put("parallel_tool_calls", false);
        // Reasoning-capable local models such as Qwen3 may spend a short
        // prefix on their internal reasoning before emitting the native tool
        // call.  Keep the probe bounded, but give it enough room to reach the
        // tool call instead of treating finish_reason=length as unsupported.
        firstBody.put("max_tokens", 256);
        firstBody.put("temperature", 0);

        var first = post(target.baseUrl() + "/chat/completions", firstBody, target.apiKey());
        var assistantResponse = first.path("choices").path(0).path("message");
        var toolCalls = assistantResponse.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.size() != 1) {
            throw new IllegalStateException("CHAT model did not return the required native tool call");
        }
        var call = toolCalls.path(0);
        if (!toolName.equals(call.path("function").path("name").asText())
                || call.path("id").asText().isBlank()) {
            throw new IllegalStateException("CHAT model returned an invalid native tool call");
        }
        validateProbeArguments(call.path("function").path("arguments").asText());

        var assistantHistory = new LinkedHashMap<String, Object>();
        assistantHistory.put("role", "assistant");
        assistantHistory.put("content", assistantResponse.path("content").isTextual()
                ? assistantResponse.path("content").asText() : null);
        assistantHistory.put("tool_calls", objectMapper.convertValue(toolCalls, Object.class));
        var messages = new ArrayList<Object>();
        messages.add(firstBody.get("messages") instanceof List<?> originals ? originals.getFirst() : Map.of());
        messages.add(assistantHistory);
        messages.add(Map.of(
                "role", "tool",
                "tool_call_id", call.path("id").asText(),
                "content", "{\"status\":\"ok\",\"nonce\":\"probe\"}"
        ));
        // Some OpenAI-compatible gateways omit assistant content when the
        // tool result itself is the final turn.  A tiny explicit confirmation
        // turn still exercises the native assistant/tool pairing while making
        // the probe unambiguous and provider-independent.
        messages.add(Map.of("role", "user", "content", "Now confirm the tool result with OK."));
        var second = post(target.baseUrl() + "/chat/completions", Map.of(
                "model", target.modelName(),
                "messages", messages,
                "max_tokens", 512,
                "temperature", 0
        ), target.apiKey());
        var finalContent = second.path("choices").path(0).path("message").path("content").asText();
        if (finalContent.isBlank()) {
            throw new IllegalStateException("CHAT model did not accept the native Tool Result");
        }
        return Map.of(
                "protocol", "openai-chat-completions",
                "model", first.path("model").asText(target.modelName()),
                "toolCalling", true,
                "toolProbeRoundTrips", 2
        );
    }

    private void validateProbeArguments(String arguments) {
        try {
            var value = objectMapper.readTree(arguments);
            if (!"probe".equals(value.path("nonce").asText())) {
                throw new IllegalStateException("CHAT model returned incorrect tool arguments");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("CHAT model returned malformed tool arguments", exception);
        }
    }

    private Map<String, Object> probeOllama(ProbeTarget target) {
        return switch (target.profileType()) {
            case CHAT, QUERY_REWRITE -> {
                post(target.baseUrl() + "/api/chat", Map.of(
                        "model", target.modelName(),
                        "messages", List.of(Map.of("role", "user", "content", "Reply with OK.")),
                        "stream", false
                ), null);
                yield Map.of("protocol", "ollama-chat", "model", target.modelName(),
                        "toolCalling", false);
            }
            case EMBEDDING -> {
                var json = post(target.baseUrl() + "/api/embed",
                        Map.of("model", target.modelName(), "input", List.of("健康检查")), null);
                var embedding = json.path("embeddings").path(0);
                if (!embedding.isArray() || embedding.isEmpty()) {
                    throw new IllegalStateException("Ollama returned no embedding vector");
                }
                yield Map.of("protocol", "ollama-embed", "model", target.modelName(),
                        "dimension", embedding.size(), "toolCalling", false);
            }
            case RERANK -> throw new IllegalArgumentException("OLLAMA rerank profiles are not supported");
        };
    }

    private Map<String, Object> probeLocalBge(ProbeTarget target) {
        var health = get(target.baseUrl() + "/health");
        if (!"ok".equalsIgnoreCase(health.path("status").asText())) {
            throw new IllegalStateException("Local BGE service is not healthy");
        }
        return switch (target.profileType()) {
            case EMBEDDING -> {
                var json = post(target.baseUrl() + "/v1/embeddings",
                        Map.of("model", target.modelName(), "input", List.of("健康检查")), null);
                var embedding = json.path("data").path(0).path("embedding");
                if (!embedding.isArray() || embedding.isEmpty()) {
                    throw new IllegalStateException("Local BGE returned no embedding vector");
                }
                yield Map.of("protocol", "openai-embeddings", "model", target.modelName(),
                        "dimension", embedding.size(), "device", health.path("device").asText("unknown"));
            }
            case RERANK -> {
                var result = rerankProbe(target);
                var capabilities = new LinkedHashMap<>(result);
                capabilities.put("device", health.path("device").asText("unknown"));
                yield Map.copyOf(capabilities);
            }
            case CHAT, QUERY_REWRITE -> throw new IllegalArgumentException(
                    "LOCAL_BGE only supports EMBEDDING and RERANK profiles");
        };
    }

    private Map<String, Object> rerankProbe(ProbeTarget target) {
        var json = post(target.baseUrl() + "/rerank", Map.of(
                "model", target.modelName(),
                "query", "知识库健康检查",
                "documents", List.of("知识库服务运行正常", "无关文本")
        ), target.apiKey());
        if (!json.path("results").isArray() || json.path("results").isEmpty()) {
            throw new IllegalStateException("Rerank endpoint returned no results");
        }
        return Map.of("protocol", "rerank", "model", target.modelName());
    }

    private JsonNode get(String url) {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET().build();
        return send(request);
    }

    private JsonNode post(String url, Object body, String apiKey) {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return send(builder.build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Model endpoint returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model profile test was interrupted", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Model endpoint request failed", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize model probe request", exception);
        }
    }
}
