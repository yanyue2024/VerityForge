package com.yanyue.rag.contract.parser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record NormalizedDocument(
        String schemaVersion,
        String parserName,
        String parserVersion,
        String title,
        String sourceName,
        String contentHash,
        Instant parsedAt,
        Map<String, Object> metadata,
        String normalizedMarkdown,
        ParseQualityReport quality,
        List<NormalizedBlock> blocks
) {
    public NormalizedDocument {
        schemaVersion = schemaVersion == null ? "2.0" : schemaVersion;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        normalizedMarkdown = normalizedMarkdown == null ? "" : normalizedMarkdown;
        quality = quality == null ? ParseQualityReport.legacyPass() : quality;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
