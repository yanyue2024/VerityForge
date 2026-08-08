package com.yanyue.rag.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DocumentControllerChunkMarkdownTest {

    private final DocumentController controller = new DocumentController(
            null, null, null, new ObjectMapper(), null, "rag-assets");

    @Test
    void preservesStructuralMarkdownForDisplay() {
        assertThat(render("Deploy", "HEADING", "{\"headingLevel\":2}"))
                .isEqualTo("### Deploy");
        assertThat(render("• first\n• second", "LIST", "{}"))
                .isEqualTo("- first\n- second");
        assertThat(render("SELECT 1;", "CODE", "{\"language\":\"sql\"}"))
                .isEqualTo("```sql\nSELECT 1;\n```");
        assertThat(render("Use carefully.", "ADMONITION", "{\"admonition\":\"caution\"}"))
                .isEqualTo("> Use carefully.");
    }

    @Test
    void fallsBackToPlainTextWhenOffsetsAreInvalid() {
        var segments = "[{\"start\":0,\"end\":99,\"type\":\"CODE\",\"attributes\":{}}]";

        assertThat(controller.renderChunkMarkdown("short", segments)).isEqualTo("short");
    }

    private String render(String text, String type, String attributes) {
        var segments = "[{\"start\":0,\"end\":" + text.length()
                + ",\"type\":\"" + type + "\",\"attributes\":" + attributes + "}]";
        return controller.renderChunkMarkdown(text, segments);
    }
}
