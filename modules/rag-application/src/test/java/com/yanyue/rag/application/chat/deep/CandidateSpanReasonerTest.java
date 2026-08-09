package com.yanyue.rag.application.chat.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateSpanReasonerTest {
    @Test
    void parsesTheExactPromptContract() {
        var requirementId = UUID.randomUUID();
        var reasoner = new CandidateSpanReasoner(null, new ObjectMapper());

        var selections = reasoner.parse("""
                {"selections":[{"spanId":"span-1","requirementIds":["%s"]}]}
                """.formatted(requirementId));

        assertEquals(1, selections.size());
        assertEquals("span-1", selections.getFirst().spanId());
        assertEquals(java.util.Set.of(requirementId), selections.getFirst().requirementIds());
    }
}
