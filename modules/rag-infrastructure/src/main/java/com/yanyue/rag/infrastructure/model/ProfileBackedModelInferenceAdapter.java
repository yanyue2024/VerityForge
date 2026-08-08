package com.yanyue.rag.infrastructure.model;

import com.yanyue.rag.application.telemetry.RagTelemetry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.model.EmbeddingModelReference;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.port.CredentialCipher;
import com.yanyue.rag.domain.port.EmbeddingModelPort;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.infrastructure.retrieval.DeterministicEmbedding;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProfileBackedModelInferenceAdapter implements EmbeddingModelPort, RerankModelPort {
    private final ModelProfileRepository profiles;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final DeterministicEmbedding deterministicEmbedding = new DeterministicEmbedding();
    private final RagTelemetry telemetry;

    public ProfileBackedModelInferenceAdapter(
            ModelProfileRepository profiles,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            @Value("${rag.models.inference-connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${rag.models.inference-request-timeout-seconds:60}") long requestTimeoutSeconds,
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
        this.telemetry = telemetry;
    }

    @Override
    public List<List<Float>> embed(EmbeddingModelReference model, List<String> texts) {
        return embed(model, texts, requestTimeout);
    }

    @Override
    public List<List<Float>> embed(EmbeddingModelReference model, List<String> texts, Duration timeout) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (texts.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Embedding inputs must not be blank");
        }
        if (model.profileId() == null) {
            return telemetry.observe("rag.model.request", Map.of(
                    "provider", "LOCAL", "model", model.modelId(), "operation", "embedding"),
                    () -> deterministic(model, texts));
        }
        var profile = requireProfile(model.profileId(), ModelProfileType.EMBEDDING);
        return telemetry.observe("rag.model.request", tags(profile, "embedding"), () -> {
            var response = switch (profile.provider()) {
                case LOCAL_BGE -> post(profile.baseUrl() + "/v1/embeddings",
                        Map.of("model", profile.modelName(), "input", texts, "dimensions", model.dimension()),
                        null, timeout);
                case OPENAI_COMPATIBLE -> post(profile.baseUrl() + "/embeddings",
                        Map.of("model", profile.modelName(), "input", texts, "dimensions", model.dimension()),
                        apiKey(profile), timeout);
                case OLLAMA -> post(profile.baseUrl() + "/api/embed",
                        Map.of("model", profile.modelName(), "input", texts), null, timeout);
                case DEMO -> throw new IllegalStateException("Demo profiles cannot provide persisted embeddings");
            };
            var vectors = profile.provider() == ModelProvider.OLLAMA
                    ? parseOllamaEmbeddings(response, texts.size())
                    : parseOpenAiEmbeddings(response, texts.size());
            validateDimensions(vectors, model.dimension());
            return vectors;
        });
    }

    @Override
    public List<RerankScore> rerank(UUID profileId, String query, List<String> documents, int topK) {
        return rerank(profileId, query, documents, topK, requestTimeout);
    }

    @Override
    public List<RerankScore> rerank(
            UUID profileId,
            String query,
            List<String> documents,
            int topK,
            Duration timeout
    ) {
        if (documents == null || documents.isEmpty() || topK <= 0) return List.of();
        var profile = requireProfile(profileId, ModelProfileType.RERANK);
        if (profile.provider() == ModelProvider.OLLAMA || profile.provider() == ModelProvider.DEMO) {
            throw new IllegalStateException(profile.provider() + " does not support reranking");
        }
        return telemetry.observe("rag.model.request", tags(profile, "rerank"), () -> {
            var response = post(profile.baseUrl() + "/rerank", Map.of(
                    "model", profile.modelName(),
                    "query", query,
                    "documents", documents,
                    "top_n", Math.min(topK, documents.size())
            ), profile.provider() == ModelProvider.OPENAI_COMPATIBLE ? apiKey(profile) : null, timeout);
            var results = new ArrayList<RerankScore>();
            for (var item : response.path("results")) {
                var index = item.path("index").asInt(-1);
                if (index < 0 || index >= documents.size()) {
                    throw new IllegalStateException("Rerank endpoint returned an invalid document index");
                }
                results.add(new RerankScore(index, item.path("relevance_score").asDouble()));
            }
            if (results.isEmpty()) throw new IllegalStateException("Rerank endpoint returned no results");
            return results.stream()
                    .sorted(Comparator.comparingDouble(RerankScore::score).reversed())
                    .limit(topK)
                    .toList();
        });
    }

    private Map<String, String> tags(ModelProfile profile, String operation) {
        return Map.of("provider", profile.provider().name(), "model", profile.modelName(),
                "operation", operation);
    }

    private List<List<Float>> deterministic(EmbeddingModelReference model, List<String> texts) {
        if (!"deterministic-local".equals(model.modelId()) || model.dimension() != DeterministicEmbedding.DIMENSION) {
            throw new IllegalStateException("A persisted embedding Profile is required for this Generation");
        }
        return texts.stream().map(text -> {
            var vector = deterministicEmbedding.embed(text);
            var values = new ArrayList<Float>(vector.length);
            for (float value : vector) values.add(value);
            return List.copyOf(values);
        }).toList();
    }

    private ModelProfile requireProfile(UUID profileId, ModelProfileType expectedType) {
        var profile = profiles.findById(profileId)
                .orElseThrow(() -> new IllegalStateException("Model profile was not found"));
        if (!profile.enabled() || profile.profileType() != expectedType) {
            throw new IllegalStateException("Model profile is disabled or has the wrong capability");
        }
        return profile;
    }

    private String apiKey(ModelProfile profile) {
        if (!profile.hasApiKey()) throw new IllegalStateException("Model profile has no API key");
        return credentialCipher.decrypt(profile.encryptedApiKey());
    }

    private List<List<Float>> parseOpenAiEmbeddings(JsonNode response, int expectedCount) {
        var vectors = new ArrayList<List<Float>>(java.util.Collections.nCopies(expectedCount, null));
        for (var item : response.path("data")) {
            var index = item.path("index").asInt(-1);
            if (index < 0 || index >= expectedCount || vectors.get(index) != null) {
                throw new IllegalStateException("Embedding endpoint returned an invalid index");
            }
            vectors.set(index, floatValues(item.path("embedding")));
        }
        if (vectors.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException("Embedding endpoint returned an incomplete batch");
        }
        return List.copyOf(vectors);
    }

    private List<List<Float>> parseOllamaEmbeddings(JsonNode response, int expectedCount) {
        var values = response.path("embeddings");
        if (!values.isArray() || values.size() != expectedCount) {
            throw new IllegalStateException("Ollama returned an incomplete embedding batch");
        }
        var vectors = new ArrayList<List<Float>>(expectedCount);
        values.forEach(value -> vectors.add(floatValues(value)));
        return List.copyOf(vectors);
    }

    private List<Float> floatValues(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalStateException("Embedding endpoint returned an empty vector");
        }
        var vector = new ArrayList<Float>(value.size());
        value.forEach(number -> vector.add(number.floatValue()));
        return List.copyOf(vector);
    }

    private void validateDimensions(List<List<Float>> vectors, int expectedDimension) {
        if (vectors.stream().anyMatch(vector -> vector.size() != expectedDimension)) {
            throw new IllegalStateException("Embedding dimension does not match the Index Generation");
        }
    }

    private JsonNode post(String url, Object body, String apiKey, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("模型推理剩余超时必须为正数");
        }
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout.compareTo(timeout) <= 0 ? requestTimeout : timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)));
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        try {
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Model inference endpoint returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model inference was interrupted", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Model inference request failed", exception);
        }
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize model inference request", exception);
        }
    }
}
