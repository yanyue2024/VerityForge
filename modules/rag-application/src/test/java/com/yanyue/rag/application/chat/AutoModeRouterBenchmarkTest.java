package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.RunMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class AutoModeRouterBenchmarkTest {
    private final AutoModeRouter router = new AutoModeRouter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesAllExplicitFastCasesAndProtectsMostConservativeDeepLabels() throws IOException {
        var cases = cases("benchmarks/chinese-enterprise-auto-routing-v1.blueprint.json");
        int expectedFast = 0;
        int expectedFastSelectedFast = 0;
        int expectedDeep = 0;
        int expectedDeepSelectedDeep = 0;
        int selectedFast = 0;
        for (var item : cases) {
            var expected = RunMode.valueOf(item.path("metadata").path("recommendedMode").asText());
            var selected = router.route(item.path("question").asText()).mode();
            if (expected == RunMode.FAST) {
                expectedFast++;
                if (selected == RunMode.FAST) expectedFastSelectedFast++;
            } else {
                expectedDeep++;
                if (selected == RunMode.DEEP) expectedDeepSelectedDeep++;
            }
            if (selected == RunMode.FAST) selectedFast++;
        }

        assertEquals(200, cases.size());
        assertEquals(100, expectedFast);
        assertEquals(expectedFast, expectedFastSelectedFast);
        assertEquals(100, expectedDeep);
        assertEquals(88, expectedDeepSelectedDeep);
        assertEquals(112, selectedFast);
    }

    @Test
    void selectsExpectedHighConfidenceSubsetFromHardBenchmark() throws IOException {
        var cases = cases("benchmarks/chinese-enterprise-agentic-retrieval-v1.blueprint.json");
        int selectedFast = 0;
        for (var item : cases) {
            if (router.route(item.path("question").asText()).mode() == RunMode.FAST) selectedFast++;
        }

        assertEquals(200, cases.size());
        assertEquals(28, selectedFast);
    }

    @Test
    void localRoutingP95StaysBelowTwoMilliseconds() throws IOException {
        var cases = cases("benchmarks/chinese-enterprise-auto-routing-v1.blueprint.json");
        for (int round = 0; round < 20; round++) {
            for (var item : cases) router.route(item.path("question").asText());
        }

        var latencies = new ArrayList<Long>();
        for (var item : cases) {
            long started = System.nanoTime();
            router.route(item.path("question").asText());
            latencies.add(System.nanoTime() - started);
        }
        Collections.sort(latencies);
        long p95Nanos = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
        assertTrue(p95Nanos < 2_000_000, "Router P95 was " + p95Nanos + " ns");
    }

    private JsonNode cases(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve(relativePath))) {
            current = current.getParent();
        }
        if (current == null) throw new IOException("Repository file was not found: " + relativePath);
        return objectMapper.readTree(current.resolve(relativePath).toFile()).path("cases");
    }
}
