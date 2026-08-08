package com.yanyue.rag.infrastructure.model;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.http.Fault;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yanyue.rag.contract.model.ModelProfileTestStatus;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.port.CredentialCipher;
import com.yanyue.rag.domain.port.AgentChatModelPort;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import com.yanyue.rag.domain.port.StreamingAnswerModelPort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleLanguageModelAdapterTest {
    private HttpServer server;
    private WireMockServer wireMock;
    private volatile String lastRequestBody;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void repairsInvalidQueryRewriteJsonOnce() throws Exception {
        var requests = new AtomicInteger();
        start(exchange -> {
            var content = requests.getAndIncrement() == 0
                    ? "not-json"
                    : "{\"rewriteNeeded\":true,\"standaloneQuery\":\"独立问题\",\"resolvedReferences\":[\"它\"]}";
            json(exchange, 200, new ObjectMapper().writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content)))
            )));
        });
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.QUERY_REWRITE));

        var result = adapter.rewrite(profileId, "它是什么？", List.of("user: 上一个主题"));

        assertEquals(2, requests.get());
        assertEquals("独立问题", result.standaloneQuery());
        assertEquals(List.of("它"), result.resolvedReferences());
        var sent = new ObjectMapper().readTree(lastRequestBody);
        assertEquals(1024, sent.path("max_tokens").asInt());
        assertEquals(0.0, sent.path("temperature").asDouble());
        assertEquals("json_object", sent.path("response_format").path("type").asText());
        assertFalse(sent.path("messages").path(0).path("content").asText().contains("json"));
        assertTrue(sent.path("messages").path(1).path("content").asText().contains("json"));
        assertFalse(sent.has("reasoning_effort"));
    }

    @Test
    void forwardsProfileReasoningEffortForStructuredRequests() throws Exception {
        start(exchange -> json(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"));
        var profileId = UUID.randomUUID();
        var now = Instant.parse("2026-07-13T00:00:00Z");
        var profile = new ModelProfile(
                profileId, UUID.randomUUID(), ModelProfileType.CHAT, ModelProvider.OPENAI_COMPATIBLE,
                "Test", "test-model", modelBaseUrl(), "encrypted-key",
                Map.of("temperature", 0, "reasoningEffort", "LOW"), true,
                ModelProfileTestStatus.PASSED, now, "ok", Map.of(), now, now);
        var adapter = adapter(profile);

        assertEquals("{}", adapter.completeJson(profileId, "evidence-extraction", "system", "user"));

        var sent = new ObjectMapper().readTree(lastRequestBody);
        assertEquals("low", sent.path("reasoning_effort").asText());
    }

    @Test
    void supportsAnIndependentTemperatureForSuggestionGeneration() throws Exception {
        start(exchange -> json(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));

        assertEquals("{}", adapter.completeJson(profileId, "question-suggestions", "system", "user",
                Duration.ofSeconds(10), 800, 1, 0.55));

        var sent = new ObjectMapper().readTree(lastRequestBody);
        assertEquals(0.55, sent.path("temperature").asDouble());
        assertEquals(800, sent.path("max_tokens").asInt());
    }

    @Test
    void fallsBackToOriginalQueryAfterRepairAlsoFails() throws Exception {
        var requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            json(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"still invalid\"}}]}");
        });
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.QUERY_REWRITE));

        var result = adapter.rewrite(profileId, "原始问题", List.of("user: context"));

        assertEquals(2, requests.get());
        assertFalse(result.rewriteNeeded());
        assertEquals("原始问题", result.standaloneQuery());
    }

    @Test
    void forwardsNativeSseDeltasAndUsage() throws Exception {
        start(exchange -> sse(exchange, 200, """
                data: {"choices":[{"delta":{"content":"真"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"实"},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2}}

                data: [DONE]

                """));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));
        var deltas = new ArrayList<String>();

        var result = adapter.generate(profileId, request(), deltas::add);

        assertEquals(List.of("真", "实"), deltas);
        assertEquals("真实", result.content());
        assertEquals(12, result.inputTokens());
        assertEquals(2, result.outputTokens());
        assertEquals("stop", result.finishReason());
    }

    @Test
    void retriesServerFailuresOnlyBeforeStreamingStarts() throws Exception {
        var requests = new AtomicInteger();
        start(exchange -> {
            if (requests.getAndIncrement() < 2) {
                json(exchange, 503, "{\"error\":\"busy\"}");
            } else {
                sse(exchange, 200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
            }
        });
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));

        var result = adapter.generate(profileId, request(), ignored -> { });

        assertEquals(3, requests.get());
        assertEquals("ok", result.content());
    }

    @Test
    void doesNotReplayAfterAStreamHasStarted() throws Exception {
        var requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            sse(exchange, 200, """
                    data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}

                    data: not-json

                    """);
        });
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));
        var deltas = new ArrayList<String>();

        assertThrows(IllegalStateException.class, () -> adapter.generate(profileId, request(), deltas::add));
        assertEquals(1, requests.get());
        assertEquals(List.of("partial"), deltas);
    }

    @Test
    void retriesRateLimitResponsesBeforeStreamingStarts() {
        startWireMock();
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"limited\"}"))
                .willSetStateTo("second-attempt"));
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("second-attempt")
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"limited\"}"))
                .willSetStateTo("success"));
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("success")
                .willReturn(sseResponse("""
                        data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """)));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));

        var result = adapter.generate(profileId, request(), ignored -> { });

        assertEquals("ok", result.content());
        assertEquals(3, wireMock.getAllServeEvents().size());
    }

    @Test
    void acceptsStreamingResponsesWithoutUsage() {
        startWireMock();
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(sseResponse("""
                        data: {"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """)));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));

        var result = adapter.generate(profileId, request(), ignored -> { });

        assertEquals("answer", result.content());
        assertNull(result.inputTokens());
        assertNull(result.outputTokens());
        assertEquals("stop", result.finishReason());
    }

    @Test
    void surfacesStructuredRequestTimeoutWithoutRetryingTransportFailures() {
        startWireMock();
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(1_500)
                        .withBody("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}")));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT), 1);

        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.completeJson(profileId, "timeout-test", "system", "user"));

        assertEquals("timeout-test request failed", failure.getMessage());
        assertEquals(1, wireMock.getAllServeEvents().size());
    }

    @Test
    void retriesOneStructuredServerFailureAndThenSucceeds() {
        startWireMock();
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("structured-server-failure")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"busy\"}"))
                .willSetStateTo("success"));
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("structured-server-failure")
                .whenScenarioStateIs("success")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}")));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));

        assertEquals("{}", adapter.completeJson(profileId, "structured-test", "system", "user"));
        assertEquals(2, wireMock.getAllServeEvents().size());
    }

    @Test
    void capsStructuredServerFailureAtThreeAttemptsAndSurfacesTheResponseBody() {
        startWireMock();
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"still busy\"}")));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT), 10);

        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.completeJson(profileId, "structured-test", "system", "user"));

        assertEquals("structured-test returned HTTP 503: {\"error\":\"still busy\"}", failure.getMessage());
        assertEquals(3, wireMock.getAllServeEvents().size());
    }

    @Test
    void doesNotExtendTheLogicalDeadlineToFitAServerRetry() {
        startWireMock();
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"busy\"}")));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT), 1);

        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.completeJson(profileId, "structured-test", "system", "user"));

        assertEquals("structured-test returned HTTP 503: {\"error\":\"busy\"}", failure.getMessage());
        assertEquals(1, wireMock.getAllServeEvents().size());
    }

    @Test
    void doesNotRetryStructuredClientErrorsAndSurfacesTheResponseBody() {
        startWireMock();
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid request\"}")));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));

        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.completeJson(profileId, "structured-test", "system", "user"));

        assertEquals("structured-test returned HTTP 400: {\"error\":\"invalid request\"}", failure.getMessage());
        assertEquals(1, wireMock.getAllServeEvents().size());
    }

    @Test
    void queryRewriteFallsBackWithoutSendingRepairAfterTransportTimeout() {
        startWireMock();
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(1_500)
                        .withBody("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}")));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.QUERY_REWRITE), 1);

        var result = adapter.rewrite(profileId, "它是什么？", List.of("user: 上一个主题"));

        assertFalse(result.rewriteNeeded());
        assertEquals("它是什么？", result.standaloneQuery());
        assertEquals(1, wireMock.getAllServeEvents().size());
    }

    @Test
    void doesNotReplayFaultedStreamingConnections() {
        startWireMock();
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withFault(Fault.RANDOM_DATA_THEN_CLOSE)));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));

        assertThrows(IllegalStateException.class,
                () -> adapter.generate(profileId, request(), ignored -> { }));
        assertEquals(1, wireMock.getAllServeEvents().size());
    }

    @Test
    void streamsAndMergesInterleavedNativeToolCallsByIndex() throws Exception {
        start(exchange -> sse(exchange, 200, """
                data: {"id":"chatcmpl-1","model":"gpt-test","system_fingerprint":"fp-1","choices":[{"index":0,"delta":{"reasoning_content":"先检索","tool_calls":[{"index":1,"id":"call-b","type":"function","function":{"name":"get_","arguments":"{\\\"knowledge_ids\\\":["}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","model":"gpt-test","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-a","type":"function","function":{"name":"knowledge_","arguments":"{\\\"queries\\\":[\\\"q\\\""}}]},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","model":"gpt-test","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"search","arguments":"]}"}},{"index":1,"function":{"name":"document_info","arguments":"\\\"d\\\"]}"}}]},"finish_reason":"tool_calls"}]}

                data: {"id":"chatcmpl-1","model":"gpt-test","choices":[],"usage":{"prompt_tokens":31,"completion_tokens":9,"total_tokens":40,"completion_tokens_details":{"reasoning_tokens":3}}}

                data: [DONE]

                """));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));
        var deltas = new ArrayList<AgentChatModelPort.AgentChatDelta>();
        var priorCall = new AgentChatModelPort.ToolCall("prior-1", "knowledge_search",
                "{\"queries\":[\"old\"]}");
        var request = new AgentChatModelPort.AgentChatRequest(
                List.of(
                        AgentChatModelPort.AgentChatMessage.system("system"),
                        AgentChatModelPort.AgentChatMessage.user("old"),
                        new AgentChatModelPort.AgentChatMessage(AgentChatModelPort.Role.ASSISTANT,
                                "", "", List.of(priorCall), null,
                                Map.of("reasoning_details", List.of(Map.of("type", "summary", "text", "old")))),
                        AgentChatModelPort.AgentChatMessage.tool("prior-1", "{\"success\":true}"),
                        AgentChatModelPort.AgentChatMessage.user("q")
                ),
                List.of(tool("knowledge_search"), tool("get_document_info")),
                AgentChatModelPort.ToolChoice.required(),
                true,
                0.2,
                512,
                30,
                true
        );

        var response = adapter.chat(profileId, request, deltas::add);

        assertEquals("先检索", response.message().reasoningContent());
        assertEquals("tool_calls", response.finishReason());
        assertEquals(List.of("call-a", "call-b"),
                response.message().toolCalls().stream().map(AgentChatModelPort.ToolCall::id).toList());
        assertEquals("knowledge_search", response.message().toolCalls().getFirst().name());
        assertEquals("{\"queries\":[\"q\"]}", response.message().toolCalls().getFirst().arguments());
        assertEquals("get_document_info", response.message().toolCalls().getLast().name());
        assertEquals("{\"knowledge_ids\":[\"d\"]}", response.message().toolCalls().getLast().arguments());
        assertEquals(31, response.usage().inputTokens());
        assertEquals(3, ((Map<?, ?>) response.usage().details()
                .get("completion_tokens_details")).get("reasoning_tokens"));
        assertEquals("gpt-test", response.providerMetadata().get("model"));
        assertTrue(deltas.stream().flatMap(delta -> delta.toolCalls().stream())
                .anyMatch(delta -> delta.index() == 1 && "get_".equals(delta.nameFragment())));

        var sent = new ObjectMapper().readTree(lastRequestBody);
        assertTrue(sent.path("stream").asBoolean());
        assertTrue(sent.path("parallel_tool_calls").asBoolean());
        assertEquals("required", sent.path("tool_choice").asText());
        assertEquals(512, sent.path("max_completion_tokens").asInt());
        assertEquals("prior-1", sent.path("messages").path(2).path("tool_calls").path(0).path("id").asText());
        assertEquals("old", sent.path("messages").path(2)
                .path("reasoning_details").path(0).path("text").asText());
        assertEquals("prior-1", sent.path("messages").path(3).path("tool_call_id").asText());
    }

    @Test
    void parsesNonStreamingToolCallsAndProviderMetadata() throws Exception {
        start(exchange -> json(exchange, 200, """
                {"id":"chatcmpl-2","model":"gpt-test","service_tier":"default",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant",
                   "content":null,"reasoning_content":"查找原文","refusal":null,"tool_calls":[
                     {"id":"call-1","type":"function","function":{"name":"grep_chunks","arguments":"{\\\"query\\\":\\\"policy\\\"}"}}
                   ]}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
                """));
        var profileId = UUID.randomUUID();
        var adapter = adapter(profile(profileId, ModelProfileType.CHAT));
        var request = new AgentChatModelPort.AgentChatRequest(
                List.of(AgentChatModelPort.AgentChatMessage.user("find policy")),
                List.of(tool("grep_chunks")), AgentChatModelPort.ToolChoice.auto(), false,
                null, 128, 30, false);

        var response = adapter.chat(profileId, request);

        assertEquals("查找原文", response.message().reasoningContent());
        assertEquals("grep_chunks", response.message().toolCalls().getFirst().name());
        assertEquals("default", response.providerMetadata().get("service_tier"));
        assertEquals(14, response.usage().totalTokens());
        var sent = new ObjectMapper().readTree(lastRequestBody);
        assertFalse(sent.path("stream").asBoolean());
        assertEquals("auto", sent.path("tool_choice").asText());
        assertFalse(sent.has("stream_options"));
    }

    private AgentChatModelPort.ToolDefinition tool(String name) {
        return new AgentChatModelPort.ToolDefinition(name, "test tool", Map.of(
                "type", "object", "properties", Map.of()
        ));
    }

    private OpenAiCompatibleLanguageModelAdapter adapter(ModelProfile profile) {
        return adapter(profile, 5);
    }

    private OpenAiCompatibleLanguageModelAdapter adapter(ModelProfile profile, long requestTimeoutSeconds) {
        var repository = mock(ModelProfileRepository.class);
        when(repository.findById(profile.id())).thenReturn(Optional.of(profile));
        var cipher = mock(CredentialCipher.class);
        when(cipher.decrypt("encrypted-key")).thenReturn("test-key");
        return new OpenAiCompatibleLanguageModelAdapter(
                repository, cipher, new ObjectMapper(), 2, requestTimeoutSeconds);
    }

    private ModelProfile profile(UUID id, ModelProfileType type) {
        var now = Instant.parse("2026-07-13T00:00:00Z");
        return new ModelProfile(id, UUID.randomUUID(), type, ModelProvider.OPENAI_COMPATIBLE,
                "Test", "test-model", modelBaseUrl(), "encrypted-key",
                Map.of("temperature", 0), true, ModelProfileTestStatus.PASSED, now, "ok", Map.of(), now, now);
    }

    private String modelBaseUrl() {
        if (wireMock != null) return wireMock.baseUrl();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void startWireMock() {
        wireMock = new WireMockServer(com.github.tomakehurst.wiremock.core.WireMockConfiguration
                .wireMockConfig().dynamicPort());
        wireMock.start();
    }

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sseResponse(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(body);
    }

    private StreamingAnswerModelPort.AnswerRequest request() {
        return new StreamingAnswerModelPort.AnswerRequest(
                "问题", "独立问题", List.of(new StreamingAnswerModelPort.AnswerEvidence(
                "E1", "Document", UUID.randomUUID(), UUID.randomUUID(), "证据")), 5);
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            handler.handle(exchange);
        });
        server.start();
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        respond(exchange, status, body);
    }

    private void sse(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        respond(exchange, status, body);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
