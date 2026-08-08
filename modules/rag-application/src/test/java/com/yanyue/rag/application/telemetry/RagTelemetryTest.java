package com.yanyue.rag.application.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagTelemetryTest {
    @Test
    void recordsBoundedOutcomeMetricsAndModelUsage() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new RagTelemetry(meters, ObservationRegistry.NOOP);

        assertEquals("ok", telemetry.observe(
                "rag.run", Map.of("mode", "FAST", "requested_mode", "AUTO"), () -> "ok"));
        assertThrows(IllegalStateException.class, () -> telemetry.observe(
                "rag.run", Map.of("mode", "DEEP", "requested_mode", "DEEP"),
                () -> { throw new IllegalStateException("failed"); }));
        telemetry.recordModelUsage("OPENAI_COMPATIBLE", "gpt-test", "answer",
                1_000, 500, Map.of("inputCostPerMillion", 2, "outputCostPerMillion", 4));

        assertEquals(1, meters.find("rag.run.total").tag("mode", "FAST")
                .tag("outcome", "success").counter().count());
        assertEquals(1, meters.find("rag.run.total").tag("mode", "DEEP")
                .tag("outcome", "error").counter().count());
        assertEquals(1_000, meters.find("rag.model.tokens").tag("direction", "input")
                .counter().count());
        assertEquals(0.004, meters.find("rag.model.cost.usd").counter().count(), 0.000001);
    }
}
