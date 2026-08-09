package com.yanyue.rag.domain.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.contract.parser.BlockType;
import com.yanyue.rag.domain.chunking.OffsetUnit;
import com.yanyue.rag.domain.knowledge.ChunkPolicy;
import com.yanyue.rag.domain.knowledge.DocumentBlock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkQualityAssessorTest {
    @Test
    void acceptsAWellMappedParentChildResultWithinHardLimits() {
        var versionId = UUID.randomUUID();
        var text = "知识库分块需要保留标题层级、来源位置和足够的上下文。".repeat(180);
        var block = block(versionId, BlockType.PARAGRAPH, 0, text);
        var policy = ChunkPolicy.defaults();
        var result = new AdaptiveParentChildChunker().chunkWithSourceMaps(versionId, List.of(block), policy);

        var report = new ChunkQualityAssessor().assess(result, List.of(block), policy);

        assertTrue(report.status() != ChunkQualityStatus.FAIL);
        assertEquals(result.chunks().size(), report.metrics().get("mappedChunks"));
        assertEquals(0, report.metrics().get("unmappedChunks"));
    }

    @Test
    void identifiesHeadingOnlyAndContextFreeSingleChildParents() {
        var versionId = UUID.randomUUID();
        var heading = block(versionId, BlockType.HEADING, 0, "只有标题");
        var policy = ChunkPolicy.defaults();
        var result = new AdaptiveParentChildChunker().chunkWithSourceMaps(versionId, List.of(heading), policy);

        var report = new ChunkQualityAssessor().assess(result, List.of(heading), policy);

        assertEquals(ChunkQualityStatus.WARNING, report.status());
        assertTrue(report.issues().stream().anyMatch(value -> value.code().equals("HEADING_ONLY_CHILDREN")));
        assertTrue(report.issues().stream().anyMatch(value -> value.code().equals("IDENTICAL_SINGLE_CHILD_PARENTS")));
    }

    @Test
    void reportsStructuralRenderMetricsForTablesAndCode() {
        var versionId = UUID.randomUUID();
        var code = block(versionId, BlockType.CODE, 0, "SELECT 1;");
        var table = block(versionId, BlockType.TABLE, 1,
                "| Name | Value |\n| --- | --- |\n| alpha | 1 |\n| beta | 2 |");
        var policy = ChunkPolicy.defaults();
        var result = new AdaptiveParentChildChunker().chunkWithSourceMaps(versionId, List.of(code, table), policy);

        var report = new ChunkQualityAssessor().assess(result, List.of(code, table), policy);

        assertEquals(0, report.metrics().get("emptyRenderChunks"));
        assertEquals(0, report.metrics().get("unfencedCodeChunks"));
        assertEquals(0, report.metrics().get("missingTableHeaderChunks"));
        assertEquals(0, report.metrics().get("anchorContaminatedChunks"));
    }

    private static DocumentBlock block(UUID versionId, BlockType type, int order, String text) {
        return new DocumentBlock(UUID.randomUUID(), versionId, type, order, text, 1, List.of(),
                0, text.length(), OffsetUnit.UTF16_CODE_UNIT, "hash-" + order, Map.of());
    }
}
