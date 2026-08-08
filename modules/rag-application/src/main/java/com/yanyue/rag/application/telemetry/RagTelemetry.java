package com.yanyue.rag.application.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class RagTelemetry {
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public RagTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    public <T> T observe(String name, Map<String, String> tags, Supplier<T> action) {
        var safeTags = safeTags(tags);
        var observation = Observation.createNotStarted(name, observations);
        if (tags != null) {
            tags.forEach((key, value) -> observation.lowCardinalityKeyValue(key, safe(value)));
        }
        observation.start();
        var outcome = "success";
        try (var ignored = observation.openScope()) {
            return action.get();
        } catch (RuntimeException | Error failure) {
            outcome = "error";
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
            var outcomeTags = safeTags.and("outcome", outcome);
            Counter.builder(name + ".total").tags(outcomeTags).register(meters).increment();
        }
    }

    public void increment(String name, Map<String, String> tags) {
        increment(name, tags, 1);
    }

    public void increment(String name, Map<String, String> tags, double amount) {
        if (amount <= 0) return;
        Counter.builder(name).tags(safeTags(tags)).register(meters).increment(amount);
    }

    public void recordModelUsage(
            String provider,
            String model,
            String operation,
            Integer inputTokens,
            Integer outputTokens,
            Map<String, Object> settings
    ) {
        var tags = Map.of(
                "provider", safe(provider),
                "model", safe(model),
                "operation", safe(operation)
        );
        increment("rag.model.tokens", with(tags, "direction", "input"), value(inputTokens));
        increment("rag.model.tokens", with(tags, "direction", "output"), value(outputTokens));
        var cost = value(inputTokens) * price(settings, "inputCostPerMillion") / 1_000_000d
                + value(outputTokens) * price(settings, "outputCostPerMillion") / 1_000_000d;
        increment("rag.model.cost.usd", tags, cost);
    }

    public static RagTelemetry noop() {
        return new RagTelemetry(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    private Tags safeTags(Map<String, String> values) {
        var tags = Tags.empty();
        if (values == null) return tags;
        for (var entry : values.entrySet()) tags = tags.and(entry.getKey(), safe(entry.getValue()));
        return tags;
    }

    private Map<String, String> with(Map<String, String> values, String key, String value) {
        var result = new LinkedHashMap<>(values);
        result.put(key, value);
        return Map.copyOf(result);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private double value(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private double price(Map<String, Object> settings, String key) {
        if (settings == null) return 0;
        var value = settings.get(key);
        if (value instanceof Number number) return Math.max(0, number.doubleValue());
        if (value instanceof String text) {
            try {
                return Math.max(0, Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
