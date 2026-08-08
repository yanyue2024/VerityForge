package com.yanyue.rag.worker.ingestion;

import java.util.Map;

record StageResult(String inputHash, String outputHash, Map<String, Object> metrics) {
    static StageResult of(String outputHash, Map<String, Object> metrics) {
        return new StageResult(null, outputHash, metrics);
    }
}
