package com.yanyue.rag.application.chat.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.domain.agent.deep.AcceptedEvidence;
import com.yanyue.rag.domain.agent.deep.EvidenceRequirementLink;
import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.SearchMode;
import com.yanyue.rag.domain.chunking.OffsetUnit;
import com.yanyue.rag.domain.chunking.SourceAnchor;
import com.yanyue.rag.domain.chunking.SourceAnchorSegment;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalAnswerEvidencePackBuilderTest {
    private final FinalAnswerEvidencePackBuilder builder = new FinalAnswerEvidencePackBuilder();

    @Test
    void mergesTheSameParentAcrossGoalsWithoutLosingCoverage() {
        var versionId = UUID.randomUUID();
        var parentId = UUID.randomUUID();
        var goalOne = UUID.randomUUID();
        var goalTwo = UUID.randomUUID();
        var requirementOne = UUID.randomUUID();
        var requirementTwo = UUID.randomUUID();
        var quote = "同一个完整父块同时解释身份来源与凭据管理方式。";

        var pack = builder.build(List.of(
                evidence(versionId, parentId, goalOne, requirementOne, quote, 0.91),
                evidence(versionId, parentId, goalTwo, requirementTwo, quote, 0.97)), 10_000);

        assertEquals(1, pack.uniqueParentCount());
        assertEquals(1, pack.evidence().size());
        var packed = pack.evidence().getFirst();
        assertEquals(Set.of(goalOne, goalTwo), packed.goalIds());
        assertEquals(Set.of(requirementOne), packed.requirementIdsByGoal().get(goalOne));
        assertEquals(Set.of(requirementTwo), packed.requirementIdsByGoal().get(goalTwo));
        assertEquals(0.97, packed.retrievalScore());
    }

    @Test
    void keepsEveryUniqueParentWhenTheTokenBudgetCanHoldThem() {
        var goal = UUID.randomUUID();
        var requirement = UUID.randomUUID();
        var evidence = List.of(
                evidence(UUID.randomUUID(), UUID.randomUUID(), goal, requirement, "父块甲提供第一组事实。", 0.99),
                evidence(UUID.randomUUID(), UUID.randomUUID(), goal, requirement, "父块乙提供第二组事实。", 0.95),
                evidence(UUID.randomUUID(), UUID.randomUUID(), goal, requirement, "父块丙提供第三组事实。", 0.90));

        var pack = builder.build(evidence, 10_000);

        assertEquals(3, pack.uniqueParentCount());
        assertEquals(3, pack.evidence().size());
        assertEquals(3, pack.evidence().stream().map(value -> value.evidence().parentChunkId()).distinct().count());
    }

    @Test
    void preservesGoalCoverageBeforeAddingAnotherParentForAnAlreadyCoveredGoal() {
        var goalOne = UUID.randomUUID();
        var goalTwo = UUID.randomUUID();
        var requirementOne = UUID.randomUUID();
        var requirementTwo = UUID.randomUUID();
        var first = evidence(UUID.randomUUID(), UUID.randomUUID(), goalOne, requirementOne,
                "目标一的最高分证据。", 0.99);
        var redundant = evidence(UUID.randomUUID(), UUID.randomUUID(), goalOne, requirementOne,
                "目标一的第二条证据。", 0.98);
        var secondGoal = evidence(UUID.randomUUID(), UUID.randomUUID(), goalTwo, requirementTwo,
                "目标二不能被高分目标挤掉。", 0.60);
        var unlimited = builder.build(List.of(first, redundant, secondGoal), 10_000);
        int twoEvidenceBudget = unlimited.evidence().stream()
                .mapToInt(FinalAnswerEvidencePackBuilder.PackedEvidence::estimatedTokens).limit(2).sum();

        var pack = builder.build(List.of(first, redundant, secondGoal), twoEvidenceBudget);

        assertEquals(2, pack.evidence().size());
        assertTrue(pack.evidence().stream().anyMatch(value -> value.goalIds().contains(goalOne)));
        assertTrue(pack.evidence().stream().anyMatch(value -> value.goalIds().contains(goalTwo)));
        assertFalse(pack.evidence().stream().anyMatch(value -> value.evidence().parentChunkId()
                .equals(redundant.parentChunkId())));
    }

    private AcceptedEvidence evidence(
            UUID versionId,
            UUID parentId,
            UUID goalId,
            UUID requirementId,
            String quote,
            double score
    ) {
        var blockId = UUID.randomUUID();
        var segment = new SourceAnchorSegment(blockId, 0, quote.length(), 0, quote.length(),
                100, 100 + quote.length(), 1);
        var anchor = new SourceAnchor(versionId, parentId, 0, quote.length(), OffsetUnit.UTF16_CODE_UNIT,
                OffsetUnit.UTF16_CODE_UNIT, OffsetUnit.UTF16_CODE_UNIT, List.of(segment));
        return new AcceptedEvidence(UUID.randomUUID(), goalId,
                List.of(EvidenceRequirementLink.primary(requirementId)), "a".repeat(64), UUID.randomUUID(),
                versionId, parentId, quote, anchor, "测试文档 / 章节", "1", score,
                ResearchPhase.PRIMARY, Set.of(UUID.randomUUID()), Set.of(SearchMode.KEYWORD, SearchMode.SEMANTIC));
    }
}
