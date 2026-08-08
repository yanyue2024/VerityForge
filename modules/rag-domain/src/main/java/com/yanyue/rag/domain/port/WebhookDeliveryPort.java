package com.yanyue.rag.domain.port;

import java.net.URI;
import java.util.Map;

public interface WebhookDeliveryPort {
    DeliveryResult deliver(URI endpoint, byte[] payload, Map<String, String> headers);

    record DeliveryResult(int statusCode, String responseBody, boolean successful, boolean retryable) {
    }
}
