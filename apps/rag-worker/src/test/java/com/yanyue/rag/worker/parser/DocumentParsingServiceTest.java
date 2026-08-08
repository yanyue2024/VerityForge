package com.yanyue.rag.worker.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class DocumentParsingServiceTest {
    @Test
    void readsTheActiveParserIdentityFromTheSidecar() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/health", exchange -> {
            var payload = """
                    {"status":"ok","parserName":"parser-sidecar","parserVersion":"0.1.2","schemaVersion":"2.0"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (var body = exchange.getResponseBody()) {
                body.write(payload);
            }
        });
        server.start();
        try {
            var service = new DocumentParsingService(new ObjectMapper(), Clock.systemUTC(),
                    mock(S3Presigner.class), "documents",
                    "http://127.0.0.1:" + server.getAddress().getPort(), 30, 40);

            assertThat(service.identity()).isEqualTo(new DocumentParsingService.ParserIdentity(
                    "parser-sidecar", "0.1.2", "2.0"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void effectiveOptionsApplyDefaultsAndAllowExplicitOverrides() {
        assertThat(DocumentParsingService.effectiveOptions(java.util.Map.of()))
                .containsEntry("ocr", "auto")
                .containsEntry("preferLayout", true);
        assertThat(DocumentParsingService.effectiveOptions(java.util.Map.of("ocr", "force")))
                .containsEntry("ocr", "force")
                .containsEntry("preferLayout", true);
    }

    @ParameterizedTest
    @CsvSource({
            "guide.md,text/markdown",
            "guide.markdown,text/x-markdown",
            "guide.html,text/html",
            "guide.htm,text/html"
    })
    void webDocumentsRequireTheParserSidecar(String fileName, String contentType) {
        var service = new DocumentParsingService(new ObjectMapper(), Clock.systemUTC(),
                mock(S3Presigner.class), "documents", "", 30, 40);

        assertThatThrownBy(() -> service.parse("source/key", fileName, contentType, "content".getBytes()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("require the parser sidecar");
    }
}
