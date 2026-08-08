package com.yanyue.rag.infrastructure.evaluation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class JdkWebhookDeliveryAdapterTest {
    private WireMockServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    void postsPayloadAndClassifiesRetryableResponses() {
        server = new WireMockServer(0);
        server.start();
        server.stubFor(post(urlEqualTo("/events")).willReturn(
                aResponse().withStatus(429).withBody("retry later")));
        var adapter = new JdkWebhookDeliveryAdapter(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                Duration.ofSeconds(2), true);

        var result = adapter.deliver(
                URI.create(server.baseUrl() + "/events"),
                "{\"event\":\"completed\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json", "X-RAG-Signature", "v1=test"));

        assertThat(result.statusCode()).isEqualTo(429);
        assertThat(result.successful()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.responseBody()).isEqualTo("retry later");
        server.verify(postRequestedFor(urlEqualTo("/events"))
                .withHeader("X-RAG-Signature", equalTo("v1=test")));
    }

    @Test
    void rejectsPrivateDestinationsByDefault() {
        var adapter = new JdkWebhookDeliveryAdapter(
                HttpClient.newHttpClient(), Duration.ofSeconds(1), false);

        assertThatThrownBy(() -> adapter.deliver(
                URI.create("http://127.0.0.1:8080/internal"), new byte[0], Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
    }

    @Test
    void springContextSelectsTheConfiguredProductionConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "rag.evaluation.notifications.connect-timeout-seconds=1",
                    "rag.evaluation.notifications.request-timeout-seconds=2",
                    "rag.evaluation.notifications.allow-private-addresses=false"
            ).applyTo(context);
            context.register(JdkWebhookDeliveryAdapter.class);
            context.refresh();

            assertThat(context.getBean(JdkWebhookDeliveryAdapter.class)).isNotNull();
        }
    }
}
