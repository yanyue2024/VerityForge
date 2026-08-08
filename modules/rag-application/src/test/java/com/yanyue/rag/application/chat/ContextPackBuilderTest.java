package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextPackBuilderTest {
    @Test
    void assignsStableEvidenceIdsAndDeduplicatesEquivalentText() {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var first = hit(UUID.randomUUID(), documentId, versionId, "同一段有效证据");
        var duplicate = hit(UUID.randomUUID(), documentId, versionId, " 同一段有效证据 ");
        var second = hit(UUID.randomUUID(), documentId, versionId, "另一段有效证据");

        var packed = new ContextPackBuilder().build(List.of(first, duplicate, second), 1000);

        assertEquals(2, packed.size());
        assertEquals("E1", packed.getFirst().evidenceId());
        assertEquals("E2", packed.getLast().evidenceId());
        assertTrue(packed.stream().allMatch(item -> item.estimatedTokens() > 0));
    }

    @Test
    void respectsTokenBudgetAfterTheFirstEvidence() {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var first = hit(UUID.randomUUID(), documentId, versionId, "甲".repeat(80));
        var second = hit(UUID.randomUUID(), documentId, versionId, "乙".repeat(80));

        var packed = new ContextPackBuilder().build(List.of(first, second), 100);

        assertEquals(1, packed.size());
    }

    private RetrievalHit hit(UUID chunkId, UUID documentId, UUID versionId, String text) {
        return new RetrievalHit(chunkId, null, documentId, versionId, "Document", text, 0.9, List.of("test"));
    }
}
