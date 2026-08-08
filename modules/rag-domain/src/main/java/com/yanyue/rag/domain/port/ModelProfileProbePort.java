package com.yanyue.rag.domain.port;

import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import java.util.Map;

public interface ModelProfileProbePort {
    ProbeResult probe(ProbeTarget target);

    record ProbeTarget(
            ModelProfileType profileType,
            ModelProvider provider,
            String modelName,
            String baseUrl,
            String apiKey,
            Map<String, Object> settings
    ) {
    }

    record ProbeResult(long latencyMs, String message, Map<String, Object> capabilities) {
    }
}
