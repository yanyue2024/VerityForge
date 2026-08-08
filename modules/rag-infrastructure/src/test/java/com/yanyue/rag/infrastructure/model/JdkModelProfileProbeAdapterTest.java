package com.yanyue.rag.infrastructure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.port.ModelProfileProbePort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JdkModelProfileProbeAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void probesForcedToolCallAndCorrespondingToolResultForOpenAiChat() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var authorizations = new CopyOnWriteArrayList<String>();
        var count = new AtomicInteger();
        start(exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            var response = count.getAndIncrement() == 0 ? """
                    {"model":"gpt-5.5","choices":[{"message":{"role":"assistant","content":null,
                    "tool_calls":[{"id":"call-probe","type":"function","function":{
                    "name":"profile_health_check","arguments":"{\\\"nonce\\\":\\\"probe\\\"}"}}]}}]}
                    """ : """
                    {"model":"gpt-5.5","choices":[{"message":{"role":"assistant","content":"OK"},
                    "finish_reason":"stop"}]}
                    """;
            respond(exchange, 200, response);
        });
        var adapter = new JdkModelProfileProbeAdapter(objectMapper, 2, 5);

        var result = adapter.probe(target(ModelProvider.OPENAI_COMPATIBLE, ModelProfileType.CHAT, "secret"));

        assertEquals(true, result.capabilities().get("toolCalling"));
        assertEquals(2, result.capabilities().get("toolProbeRoundTrips"));
        assertEquals(List.of("Bearer secret", "Bearer secret"), authorizations);
        assertEquals(2, requests.size());
        var first = objectMapper.readTree(requests.getFirst());
        assertEquals("required", first.path("tool_choice").asText());
        assertFalse(first.path("parallel_tool_calls").asBoolean());
        var second = objectMapper.readTree(requests.getLast());
        assertEquals("call-probe", second.path("messages").path(1)
                .path("tool_calls").path(0).path("id").asText());
        assertEquals("call-probe", second.path("messages").path(2).path("tool_call_id").asText());
        assertEquals("tool", second.path("messages").path(2).path("role").asText());
    }

    @Test
    void failsChatProbeWhenEndpointOnlyReturnsPlainText() throws Exception {
        start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
        });
        var adapter = new JdkModelProfileProbeAdapter(objectMapper, 2, 5);

        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.probe(target(ModelProvider.OPENAI_COMPATIBLE, ModelProfileType.CHAT, "secret")));

        assertTrue(failure.getMessage().contains("required native tool call"));
    }

    @Test
    void advertisesDemoToolCallingAndMarksOllamaUnsupported() throws Exception {
        var adapter = new JdkModelProfileProbeAdapter(objectMapper, 2, 5);
        var demo = adapter.probe(new ModelProfileProbePort.ProbeTarget(
                ModelProfileType.CHAT, ModelProvider.DEMO, "demo", null, null, Map.of()));
        assertEquals(true, demo.capabilities().get("toolCalling"));

        start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{}");
        });
        var ollama = adapter.probe(target(ModelProvider.OLLAMA, ModelProfileType.CHAT, null));
        assertEquals(false, ollama.capabilities().get("toolCalling"));
    }

    private ModelProfileProbePort.ProbeTarget target(
            ModelProvider provider,
            ModelProfileType type,
            String apiKey
    ) {
        return new ModelProfileProbePort.ProbeTarget(
                type, provider, "gpt-5.5", "http://127.0.0.1:" + server.getAddress().getPort(),
                apiKey, Map.of()
        );
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
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
