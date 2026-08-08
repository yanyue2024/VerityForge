package com.yanyue.rag.infrastructure.model;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class ModelHttpClientFactory {
    private ModelHttpClientFactory() {
    }

    static HttpClient create(Duration connectTimeout, String proxyUrl, String noProxyHosts) {
        var builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            builder.proxy(proxySelector(proxyUrl, noProxyHosts));
        }
        return builder.build();
    }

    static ProxySelector proxySelector(String proxyUrl, String noProxyHosts) {
        var uri = URI.create(proxyUrl.trim());
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Model proxy URL must include a host");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Model proxy URL must use http or https");
        }
        var port = uri.getPort() >= 0 ? uri.getPort() : (scheme.equals("https") ? 443 : 80);
        var proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), port));
        var bypassPatterns = Arrays.stream(noProxyHosts == null ? new String[0] : noProxyHosts.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI target) {
                if (target == null) throw new IllegalArgumentException("Target URI must not be null");
                var host = target.getHost();
                if (host != null && bypassPatterns.stream().anyMatch(pattern -> matches(host, pattern))) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                // Connection failures are surfaced by HttpClient to the model adapter.
            }
        };
    }

    private static boolean matches(String host, String pattern) {
        var normalizedHost = host.toLowerCase(Locale.ROOT);
        if (pattern.equals("*")) return true;
        if (pattern.startsWith("*.")) pattern = pattern.substring(1);
        if (pattern.startsWith(".")) {
            var domain = pattern.substring(1);
            return normalizedHost.equals(domain) || normalizedHost.endsWith(pattern);
        }
        return normalizedHost.equals(pattern);
    }
}
