package com.yanyue.rag.contract.parser;

import java.net.URI;
import java.util.Map;

public record ParseDocumentRequest(
        URI sourceUrl,
        URI resultUrl,
        String fileName,
        String contentType,
        String parserProfile,
        Map<String, Object> options
) {
    public ParseDocumentRequest {
        parserProfile = parserProfile == null ? "AUTO" : parserProfile;
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
