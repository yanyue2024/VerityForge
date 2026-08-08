package com.yanyue.rag.infrastructure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ModelHttpClientFactoryTest {
    @Test
    void proxiesExternalModelEndpointsAndBypassesInternalHosts() {
        var selector = ModelHttpClientFactory.proxySelector(
                "http://proxy.example.test:8080",
                "localhost,127.0.0.1,model-sidecar,.internal.example"
        );

        var external = selector.select(URI.create("https://api.example.test/v1/chat/completions")).getFirst();
        assertEquals(Proxy.Type.HTTP, external.type());
        var proxyAddress = (InetSocketAddress) external.address();
        assertEquals("proxy.example.test", proxyAddress.getHostString());
        assertEquals(8080, proxyAddress.getPort());
        assertEquals(Proxy.NO_PROXY, selector.select(URI.create("http://model-sidecar:8091/v1/rerank")).getFirst());
        assertEquals(Proxy.NO_PROXY, selector.select(URI.create("http://service.internal.example/v1")).getFirst());
    }
}
