package com.yanyue.rag.infrastructure.evaluation;

import com.yanyue.rag.domain.port.WebhookDeliveryPort;
import java.io.IOException;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JdkWebhookDeliveryAdapter implements WebhookDeliveryPort {
    private static final int MAX_RESPONSE_BYTES = 2_000;

    private final HttpClient client;
    private final Duration requestTimeout;
    private final boolean allowPrivateAddresses;

    @Autowired
    public JdkWebhookDeliveryAdapter(
            @Value("${rag.evaluation.notifications.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${rag.evaluation.notifications.request-timeout-seconds:10}") long requestTimeoutSeconds,
            @Value("${rag.evaluation.notifications.allow-private-addresses:false}") boolean allowPrivateAddresses
    ) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)), allowPrivateAddresses);
    }

    JdkWebhookDeliveryAdapter(HttpClient client, Duration requestTimeout, boolean allowPrivateAddresses) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    @Override
    public DeliveryResult deliver(URI endpoint, byte[] payload, Map<String, String> headers) {
        validate(endpoint);
        var builder = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        headers.forEach(builder::header);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (var stream = response.body()) {
                var bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                body = new String(bytes, 0, Math.min(bytes.length, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            }
            int status = response.statusCode();
            boolean successful = status >= 200 && status < 300;
            boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
            return new DeliveryResult(status, body, successful, retryable);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook delivery was interrupted", failure);
        } catch (IOException failure) {
            throw new IllegalStateException("Webhook delivery failed", failure);
        }
    }

    private void validate(URI endpoint) {
        var scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Webhook endpoint is not an allowed HTTP(S) URL");
        }
        if (allowPrivateAddresses) return;
        try {
            for (var address : InetAddress.getAllByName(IDN.toASCII(endpoint.getHost()))) {
                if (isPrivate(address)) {
                    throw new IllegalArgumentException("Webhook endpoint resolves to a private or local address");
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Webhook endpoint host cannot be resolved", failure);
        }
    }

    private boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(address.getAddress()[0]);
            return (first & 0xfe) == 0xfc;
        }
        return false;
    }
}
