package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeepReadEvidenceSelectorTest {
    private final DeepReadEvidenceSelector selector = new DeepReadEvidenceSelector();

    @Test
    void allocatesOnePhysicalReadPerTaskBeforeASecondFamily() {
        var first = group(hit(UUID.randomUUID(), UUID.randomUUID()), hit(UUID.randomUUID(), UUID.randomUUID()));
        var second = group(hit(UUID.randomUUID(), UUID.randomUUID()), hit(UUID.randomUUID(), UUID.randomUUID()));
        var third = group(hit(UUID.randomUUID(), UUID.randomUUID()), hit(UUID.randomUUID(), UUID.randomUUID()));

        var selected = selector.select(List.of(first, second, third), Set.of(), Set.of(), 3, 2, 2);

        assertEquals(3, selected.size());
        assertEquals(List.of(first.taskId(), second.taskId(), third.taskId()),
                selected.stream().map(DeepReadEvidenceSelector.Selection::taskId).toList());
        assertTrue(selected.stream().allMatch(DeepReadEvidenceSelector.Selection::physicalRead));
    }

    @Test
    void reusesOnePhysicalChunkAcrossSubQuestionsWithoutSpendingAnotherRead() {
        var shared = hit(UUID.randomUUID(), UUID.randomUUID());
        var first = group(shared);
        var second = group(shared);

        var selected = selector.select(List.of(first, second), Set.of(), Set.of(), 1, 1, 1);

        assertEquals(2, selected.size());
        assertTrue(selected.getFirst().physicalRead());
        assertFalse(selected.getLast().physicalRead());
        assertEquals(first.subQuestionId(), selected.getFirst().subQuestionId());
        assertEquals(second.subQuestionId(), selected.getLast().subQuestionId());
    }

    @Test
    void prefersAnotherDocumentVersionForTheSecondEvidenceFamily() {
        var firstVersion = UUID.randomUUID();
        var secondVersion = UUID.randomUUID();
        var first = hit(UUID.randomUUID(), firstVersion);
        var adjacent = hit(UUID.randomUUID(), firstVersion);
        var independent = hit(UUID.randomUUID(), secondVersion);
        var group = group(first, adjacent, independent);

        var selected = selector.select(List.of(group), Set.of(), Set.of(), 3, 2, 2);

        assertEquals(List.of(first.chunkId(), independent.chunkId()),
                selected.stream().map(item -> item.hit().chunkId()).toList());
    }

    @Test
    void prefersAReusableCrossQuestionHitOverAnUnreadEvidenceFamily() {
        var first = hit(UUID.randomUUID(), UUID.randomUUID());
        var unread = hit(UUID.randomUUID(), UUID.randomUUID());
        var reusable = hit(UUID.randomUUID(), UUID.randomUUID());
        var group = group(first, unread, reusable);

        var selected = selector.select(
                List.of(group), Set.of(), Set.of(first.chunkId(), reusable.chunkId()), 1, 2, 2);

        assertEquals(List.of(first.chunkId(), reusable.chunkId()),
                selected.stream().map(item -> item.hit().chunkId()).toList());
        assertTrue(selected.stream().noneMatch(DeepReadEvidenceSelector.Selection::physicalRead));
    }

    @Test
    void limitsTotalAssignmentsAcrossTasksOfTheSameSubQuestion() {
        var questionId = UUID.randomUUID();
        var first = group(questionId,
                hit(UUID.randomUUID(), UUID.randomUUID()), hit(UUID.randomUUID(), UUID.randomUUID()));
        var second = group(questionId,
                hit(UUID.randomUUID(), UUID.randomUUID()), hit(UUID.randomUUID(), UUID.randomUUID()));
        var third = group(questionId,
                hit(UUID.randomUUID(), UUID.randomUUID()), hit(UUID.randomUUID(), UUID.randomUUID()));

        var selected = selector.select(List.of(first, second, third), Set.of(), Set.of(), 6, 2, 2);

        assertEquals(2, selected.size());
        assertEquals(List.of(first.taskId(), second.taskId()),
                selected.stream().map(DeepReadEvidenceSelector.Selection::taskId).toList());
    }

    private DeepReadEvidenceSelector.CandidateGroup group(RetrievalHit... hits) {
        return group(UUID.randomUUID(), hits);
    }

    private DeepReadEvidenceSelector.CandidateGroup group(UUID subQuestionId, RetrievalHit... hits) {
        return new DeepReadEvidenceSelector.CandidateGroup(UUID.randomUUID(), subQuestionId, List.of(hits));
    }

    private RetrievalHit hit(UUID chunkId, UUID versionId) {
        return new RetrievalHit(chunkId, null, UUID.randomUUID(), versionId, "文档", "证据", 0.9,
                List.of("rerank"));
    }
}
