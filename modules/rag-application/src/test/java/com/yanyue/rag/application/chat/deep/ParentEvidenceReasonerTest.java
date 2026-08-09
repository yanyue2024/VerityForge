package com.yanyue.rag.application.chat.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.chunking.ChunkSourceMapBuilder;
import com.yanyue.rag.domain.chunking.ChildAnchor;
import com.yanyue.rag.domain.chunking.OffsetUnit;
import com.yanyue.rag.domain.chunking.PageRange;
import com.yanyue.rag.domain.chunking.ParentContext;
import com.yanyue.rag.domain.chunking.QueryProvenance;
import com.yanyue.rag.domain.chunking.SourceBlockSlice;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParentEvidenceReasonerTest {
    @Test
    void adaptiveEvidenceMustBeAnExactSubstringOfTheSelectedSourceBlock() {
        var parentId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        var mapped = new ChunkSourceMapBuilder().build(parentId, List.of(
                new SourceBlockSlice(blockId, "前置条件：必须先安装组件。其余说明。", 0,
                        "前置条件：必须先安装组件。其余说明。".length(), 10, 29,
                        OffsetUnit.UTF16_CODE_UNIT, 1)));
        var parent = new ParentContext(parentId, UUID.randomUUID(), UUID.randomUUID(),
                List.of("安装指南"), PageRange.unknown(), mapped.text(), List.of(), List.of(),
                mapped.sourceMap(), 0.9);
        var requirementId = UUID.randomUUID();
        var segment = parent.sourceMap().segments().getFirst();
        var sourceBlocks = List.of(new ParentEvidenceReasoner.SourceBlock(
                "B1", segment, parent.text()));
        var reasoner = new ParentEvidenceReasoner(null, new ObjectMapper());

        var selected = reasoner.parseAdaptive("""
                {"evidence":[{"blockId":"B1","quote":"必须先安装组件。","requirementIds":["%s"]}]}
                """.formatted(requirementId), parent, sourceBlocks, Set.of(requirementId));

        assertEquals(1, selected.size());
        assertEquals("必须先安装组件。", selected.getFirst().span().text());
        assertEquals(parent.text().indexOf("必须先安装组件。"),
                selected.getFirst().span().localStart());
    }

    @Test
    void parentDecisionRejectsUnknownRequirementsAndHonorsExplicitRejection() {
        var offered = UUID.randomUUID();
        var reasoner = new ParentEvidenceReasoner(null, new ObjectMapper());

        var stale = reasoner.parseParent("""
                {"accepted":true,"requirementIds":["%s"]}
                """.formatted(UUID.randomUUID()), Set.of(offered));
        var rejected = reasoner.parseParent(
                "{\"accepted\":false,\"requirementIds\":[]}", Set.of(offered));

        assertFalse(stale.accepted());
        assertTrue(stale.requirementIds().isEmpty());
        assertFalse(rejected.accepted());
    }

    @Test
    void batchDecisionTreatsOmittedParentsAsRejectedAndKeepsValidAcceptedParents() {
        var requirementId = UUID.randomUUID();
        var first = parent("isula-build采用服务端/客户端模式。客户端提交命令。", 0.91);
        var second = parent("这里只包含无关的卷管理内容。", 0.52);
        var reasoner = new ParentEvidenceReasoner(null, new ObjectMapper());

        var decisions = reasoner.parseBatch("""
                {"acceptedParents":[
                  {"parentChunkId":"%s","requirementIds":["%s"]}
                ]}
                """.formatted(first.parentChunkId(), requirementId),
                List.of(first, second), Set.of(requirementId));

        assertEquals(2, decisions.size());
        assertTrue(decisions.getFirst().accepted());
        assertEquals(Set.of(requirementId), decisions.getFirst().requirementIds());
        assertFalse(decisions.getLast().accepted());
        var tolerant = reasoner.parseBatch("""
                {"acceptedParents":[
                  {"parentChunkId":"%s","requirementIds":[]},
                  {"parentChunkId":"%s","requirementIds":["%s"]}
                ]}
                """.formatted(first.parentChunkId(), UUID.randomUUID(), requirementId),
                List.of(first, second), Set.of(requirementId));
        assertTrue(tolerant.getFirst().accepted());
        assertFalse(tolerant.getLast().accepted());
        assertThrows(IllegalStateException.class, () -> reasoner.parseBatch(
                "{\"unexpected\":[]}", List.of(first, second), Set.of(requirementId)));
    }

    @Test
    void batchInputIncludesMatchedChildTextAndQueryProvenance() {
        var requirementId = UUID.randomUUID();
        var goalId = UUID.randomUUID();
        var childId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var text = "目录。isula-build采用服务端/客户端模式。正文。";
        int start = text.indexOf("isula-build");
        int end = text.indexOf("正文");
        var base = parent(text, 0.9);
        var parent = new ParentContext(base.parentChunkId(), base.documentId(), base.documentVersionId(),
                base.titlePath(), base.pageRange(), base.text(), List.of(new ChildAnchor(childId, start, end)),
                List.of(new QueryProvenance(queryId.toString(), childId, 0.88)), base.sourceMap(), 0.9);
        var requirement = new com.yanyue.rag.domain.agent.deep.RequirementPlan(
                requirementId, goalId, "核验组成、职责和关系");
        var targets = Set.of(requirementId);
        var keyword = new com.yanyue.rag.domain.agent.deep.SearchQuery(queryId, goalId,
                com.yanyue.rag.domain.agent.deep.ResearchPhase.PRIMARY,
                com.yanyue.rag.domain.agent.deep.SearchQueryRole.PRIMARY_KEYWORD,
                "isula-build", com.yanyue.rag.domain.agent.deep.SearchMode.KEYWORD, targets);
        var semantic = new com.yanyue.rag.domain.agent.deep.SearchQuery(UUID.randomUUID(), goalId,
                com.yanyue.rag.domain.agent.deep.ResearchPhase.PRIMARY,
                com.yanyue.rag.domain.agent.deep.SearchQueryRole.PRIMARY_SEMANTIC,
                "说明客户端与服务端职责", com.yanyue.rag.domain.agent.deep.SearchMode.SEMANTIC, targets);
        var goal = new com.yanyue.rag.domain.agent.deep.GoalPlan(goalId, "说明构建工具的组成与作用",
                List.of(requirement), new com.yanyue.rag.domain.agent.deep.QueryPair(goalId,
                com.yanyue.rag.domain.agent.deep.ResearchPhase.PRIMARY, keyword, semantic));
        var reasoner = new ParentEvidenceReasoner(null, new ObjectMapper());

        var input = reasoner.batchInput("说明构建工具", goal,
                com.yanyue.rag.domain.agent.deep.ResearchPhase.PRIMARY, targets,
                List.of(keyword, semantic), List.of(parent));
        var parents = (List<?>) input.get("parents");
        var parentInput = (java.util.Map<?, ?>) parents.getFirst();
        var children = (List<?>) parentInput.get("matchedChildren");
        var child = (java.util.Map<?, ?>) children.getFirst();

        assertEquals(text.substring(start, end), child.get("text"));
        assertEquals(1, ((List<?>) child.get("queryMatches")).size());
    }

    private ParentContext parent(String text, double score) {
        var parentId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        var mapped = new ChunkSourceMapBuilder().build(parentId, List.of(
                new SourceBlockSlice(blockId, text, 0, text.length(), 0, text.length(),
                        OffsetUnit.UTF16_CODE_UNIT, 1)));
        return new ParentContext(parentId, UUID.randomUUID(), UUID.randomUUID(),
                List.of("测试文档"), PageRange.unknown(), mapped.text(), List.of(), List.of(),
                mapped.sourceMap(), score);
    }
}
