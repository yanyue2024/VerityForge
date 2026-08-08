package com.yanyue.rag.domain.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.contract.parser.BlockType;
import com.yanyue.rag.domain.knowledge.ChunkPolicy;
import com.yanyue.rag.domain.knowledge.ChunkType;
import com.yanyue.rag.domain.knowledge.DocumentBlock;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.ChunkSourceMapBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AdaptiveParentChildChunkerTest {
    @Test
    void createsDeterministicParentAndChildChunksWithBreadcrumbs() {
        var versionId = UUID.randomUUID();
        var text = "知识库治理需要维护文档版本和有效期。".repeat(160);
        var block = new DocumentBlock(UUID.randomUUID(), versionId, BlockType.PARAGRAPH, 0, text, 1,
                List.of("平台设计", "知识治理"), 0, text.length(), OffsetUnit.UTF16_CODE_UNIT,
                "hash", Map.of());
        var policy = new ChunkPolicy(200, 240, 20, 50, 60, 10, "test-v1");
        var chunker = new AdaptiveParentChildChunker();

        var first = chunker.chunk(versionId, List.of(block), policy);
        var second = chunker.chunk(versionId, List.of(block), policy);

        assertFalse(first.isEmpty());
        assertEquals(first, second);
        assertTrue(first.stream().anyMatch(chunk -> chunk.type() == ChunkType.PARENT));
        assertTrue(first.stream().anyMatch(chunk -> chunk.type() == ChunkType.CHILD));
        assertTrue(first.stream().filter(chunk -> chunk.type() == ChunkType.CHILD)
                .allMatch(chunk -> chunk.parentChunkId() != null && chunk.embeddingText().startsWith("Section:")));
        assertTrue(first.stream().allMatch(chunk -> chunk.estimatedTokens()
                <= (chunk.type() == ChunkType.PARENT ? 240 : 60)));
        var mapped = chunker.chunkWithSourceMaps(versionId, List.of(block), policy);
        assertTrue(mapped.sourceMaps().values().stream().allMatch(value -> value.segments().size() >= 1));
    }

    @Test
    void keepsStrongHeadingBoundariesFreeOfPreviousSectionOverlap() {
        var versionId = UUID.randomUUID();
        var blocks = List.of(
                block(versionId, BlockType.HEADING, 0, "第一章", List.of("第一章"), Map.of("headingLevel", 1)),
                block(versionId, BlockType.PARAGRAPH, 1,
                        "第一章内容只讨论账户权限与审计要求。".repeat(8), List.of("第一章"), Map.of()),
                block(versionId, BlockType.HEADING, 2, "第二章", List.of("第二章"), Map.of("headingLevel", 1)),
                block(versionId, BlockType.PARAGRAPH, 3,
                        "第二章内容只讨论数据保留与删除要求。".repeat(8), List.of("第二章"), Map.of())
        );
        var policy = new ChunkPolicy(160, 190, 20, 40, 55, 8, "heading-test");

        var parents = new AdaptiveParentChildChunker().chunk(versionId, blocks, policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.PARENT).toList();

        assertEquals(2, parents.size());
        assertTrue(parents.get(0).text().contains("第一章内容"));
        assertFalse(parents.get(0).text().contains("第二章内容"));
        assertTrue(parents.get(1).text().startsWith("第二章"));
        assertFalse(parents.get(1).text().contains("第一章内容"));
    }

    @Test
    void mergesAdjacentShortLevelTwoSectionsWithoutCreatingHeadingOnlyChunks() {
        var versionId = UUID.randomUUID();
        var blocks = List.of(
                block(versionId, BlockType.TITLE, 0, "平台手册", List.of("平台手册"),
                        Map.of("headingLevel", 1)),
                block(versionId, BlockType.HEADING, 1, "适用场景", List.of("平台手册", "适用场景"),
                        Map.of("headingLevel", 2)),
                block(versionId, BlockType.PARAGRAPH, 2,
                        "用于企业知识检索与问答。".repeat(3), List.of("平台手册", "适用场景"), Map.of()),
                block(versionId, BlockType.HEADING, 3, "配置方式", List.of("平台手册", "配置方式"),
                        Map.of("headingLevel", 2)),
                block(versionId, BlockType.PARAGRAPH, 4,
                        "管理员可以选择知识范围与过滤条件。".repeat(3), List.of("平台手册", "配置方式"), Map.of())
        );
        var policy = new ChunkPolicy(160, 190, 20, 40, 55, 8, "soft-heading-test");

        var chunks = new AdaptiveParentChildChunker().chunk(versionId, blocks, policy);
        var parents = chunks.stream().filter(chunk -> chunk.type() == ChunkType.PARENT).toList();
        var children = chunks.stream().filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertEquals(1, parents.size());
        assertTrue(parents.getFirst().text().contains("适用场景"));
        assertTrue(parents.getFirst().text().contains("配置方式"));
        assertTrue(children.stream().noneMatch(chunk -> chunk.text().equals("平台手册")
                || chunk.text().equals("适用场景") || chunk.text().equals("配置方式")));
    }

    @Test
    void boundsParentAndChildWindowsWhileKeepingDirectSourceMappings() {
        var versionId = UUID.randomUUID();
        var text = java.util.stream.IntStream.range(0, 90)
                .mapToObj(index -> "规则" + index + "要求记录访问主体、资源与结果。")
                .collect(Collectors.joining());
        var block = block(versionId, BlockType.PARAGRAPH, 0, text, List.of("平台", "审计"), Map.of());
        var policy = new ChunkPolicy(80, 100, 10, 20, 28, 5, "mapping-test");

        var result = new AdaptiveParentChildChunker().chunkWithSourceMaps(versionId, List.of(block), policy);
        var parents = result.chunks().stream().filter(chunk -> chunk.type() == ChunkType.PARENT).toList();
        var children = result.chunks().stream().filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(parents.size() > 1);
        assertTrue(children.size() > parents.size());
        assertTrue(parents.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.parentMaxTokens()));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
        result.chunks().forEach(chunk -> {
            var sourceMap = result.sourceMaps().get(chunk.id());
            assertTrue(sourceMap != null && !sourceMap.segments().isEmpty());
            var reconstructed = sourceMap.segments().stream()
                    .map(segment -> text.substring(segment.blockLocalStart(), segment.blockLocalEnd()))
                    .collect(Collectors.joining(ChunkSourceMapBuilder.SYNTHETIC_BLOCK_SEPARATOR));
            assertEquals(chunk.text(), reconstructed);
        });

        var firstEnd = result.sourceMaps().get(parents.get(0).id()).segments().getLast().blockLocalEnd();
        var secondStart = result.sourceMaps().get(parents.get(1).id()).segments().getFirst().blockLocalStart();
        assertTrue(secondStart < firstEnd, "adjacent capacity-bounded parents should retain source overlap");
    }

    @Test
    void splitsAContinuousLexicalRunWithoutExceedingHardLimits() {
        var versionId = UUID.randomUUID();
        var text = "1415926535".repeat(240);
        var block = block(versionId, BlockType.PARAGRAPH, 0, text, List.of("Job"), Map.of());
        var policy = new ChunkPolicy(1000, 1200, 100, 250, 384, 40, "hard-limit-test");

        var chunks = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy);
        var children = chunks.stream().filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() > 1);
        assertTrue(chunks.stream().filter(chunk -> chunk.type() == ChunkType.PARENT)
                .allMatch(chunk -> chunk.estimatedTokens() <= policy.parentMaxTokens()));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
    }

    @Test
    void doesNotSplitOrdinaryLatinIdentifiersAcrossChunkBoundaries() {
        var versionId = UUID.randomUUID();
        var text = java.util.stream.IntStream.range(0, 160)
                .mapToObj(index -> "configuration volatility immutable statement" + index + ".")
                .collect(Collectors.joining(" "));
        var block = block(versionId, BlockType.CODE, 0, text, List.of("Python UDF"), Map.of());
        var policy = new ChunkPolicy(180, 220, 20, 35, 45, 5, "lexical-boundary-test");

        var children = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() > 1);
        assertTrue(children.stream().allMatch(chunk -> !chunk.text().startsWith("atility")));
        assertTrue(children.stream().allMatch(chunk -> !chunk.text().endsWith("volatilit")));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
    }

    @Test
    void overlapRetainsOnlyCompleteSemanticUnits() {
        var versionId = UUID.randomUUID();
        var text = java.util.stream.IntStream.range(0, 36)
                .mapToObj(index -> "规则" + index + "有效。")
                .collect(Collectors.joining());
        var block = block(versionId, BlockType.PARAGRAPH, 0, text, List.of("平台", "规则"), Map.of());
        var policy = new ChunkPolicy(80, 100, 10, 18, 24, 8, "semantic-overlap-test");

        var result = new AdaptiveParentChildChunker().chunkWithSourceMaps(versionId, List.of(block), policy);
        var children = result.chunks().stream().filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() > 2);
        assertTrue(children.stream().allMatch(chunk -> chunk.text().startsWith("规则")));
        assertTrue(children.stream().allMatch(chunk -> !chunk.text().matches("^[，。；：、)].*")));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
        assertTrue(java.util.stream.IntStream.range(1, children.size()).anyMatch(index -> {
            var previous = result.sourceMaps().get(children.get(index - 1).id()).segments().getLast();
            var current = result.sourceMaps().get(children.get(index).id()).segments().getFirst();
            return current.blockLocalStart() < previous.blockLocalEnd();
        }));
    }

    @Test
    void keepsCodeClosingAndContinuationLinesWithThePrecedingStatement() {
        var versionId = UUID.randomUUID();
        var statement = """
                CREATE FUNCTION demo(INT)
                RETURNS INT
                PROPERTIES (
                "type" = "PYTHON_UDF",
                "symbol" = "evaluate",
                "runtime_version" = "3.10.12"
                )
                AS $$
                def evaluate(value):
                    return value + 1
                $$;
                """;
        var text = statement.repeat(18);
        var block = block(versionId, BlockType.CODE, 0, text, List.of("Python UDF"), Map.of());
        var policy = new ChunkPolicy(160, 200, 20, 48, 72, 8, "code-continuation-test");

        var children = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() > 2);
        assertTrue(children.stream().allMatch(chunk -> !chunk.text().matches("^[)}\\],;].*")));
        assertTrue(children.stream().allMatch(chunk -> !chunk.text().startsWith("AS $$")));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
    }

    @Test
    void keepsSourceTextExactWhileRenderingCodeWithItsLanguageFence() {
        var versionId = UUID.randomUUID();
        var code = "SELECT catalog_name FROM internal.catalogs;";
        var block = block(versionId, BlockType.CODE, 0, code, List.of("Hive Catalog", "查询"),
                Map.of("language", "sql"));
        var policy = new ChunkPolicy(1000, 1200, 100, 250, 384, 40, "render-code-test");

        var child = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).findFirst().orElseThrow();

        assertEquals(code, child.text());
        assertEquals("```sql\n" + code + "\n```", child.renderMarkdown());
        assertTrue(child.embeddingText().contains(child.renderMarkdown()));
    }

    @Test
    void rendersUnmarkedListBlocksWithoutChangingSourceText() {
        var versionId = UUID.randomUUID();
        var source = "FeatureOne=true|false (BETA)\nFeatureTwo=true|false (ALPHA)";
        var block = block(versionId, BlockType.LIST, 0, source, List.of("Kubelet", "Feature gates"),
                Map.of("structureDetected", "FEATURE_GATE_LIST"));
        var policy = new ChunkPolicy(1000, 1200, 100, 250, 384, 40, "render-list-test");

        var child = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).findFirst().orElseThrow();

        assertEquals(source, child.text());
        assertEquals("- FeatureOne=true|false (BETA)\n- FeatureTwo=true|false (ALPHA)",
                child.renderMarkdown());
        assertTrue(child.embeddingText().contains(child.renderMarkdown()));
    }

    @Test
    void prependsVirtualHeadersWhenAChildStartsInsideAMarkdownTable() {
        var versionId = UUID.randomUUID();
        var rows = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> "| key-" + index + " | value-" + index + " with useful details |")
                .collect(Collectors.joining("\n"));
        var table = "| Key | Value |\n| --- | --- |\n" + rows;
        var block = block(versionId, BlockType.TABLE, 0, table, List.of("Hive Catalog", "Properties"), Map.of());
        var policy = new ChunkPolicy(180, 220, 20, 36, 50, 5, "render-table-test");

        var children = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() > 1);
        assertTrue(children.stream().skip(1).allMatch(chunk ->
                chunk.renderMarkdown().startsWith("| Key | Value |\n| --- | --- |\n")));
        assertTrue(children.stream().skip(1).allMatch(chunk ->
                !chunk.text().startsWith("| Key | Value |")));
    }

    @Test
    void absorbsATinyTerminalTailWhenThePreviousWindowHasCapacity() {
        var versionId = UUID.randomUUID();
        var text = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> "第" + index + "条规则要求保留完整的审计记录。")
                .collect(Collectors.joining());
        var block = block(versionId, BlockType.PARAGRAPH, 0, text, List.of("审计规则"), Map.of());
        var policy = new ChunkPolicy(1000, 1200, 100, 60, 100, 8, "short-tail-test");

        var children = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() >= 2);
        assertTrue(children.getLast().estimatedTokens() >= Math.round(policy.childTargetTokens() * 0.48f));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
    }

    @Test
    void keepsClosingPunctuationOffTheStartOfTheNextTokenWindow() {
        var versionId = UUID.randomUUID();
        var text = java.util.stream.IntStream.range(0, 120)
                .mapToObj(index -> "feature" + index + "=true)")
                .collect(Collectors.joining(" "));
        var block = block(versionId, BlockType.PARAGRAPH, 0, text, List.of("Feature gates"), Map.of());
        var policy = new ChunkPolicy(160, 200, 20, 30, 36, 4, "closing-boundary-test");

        var children = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() > 2);
        assertTrue(children.stream().allMatch(chunk -> !chunk.text().matches("^[)}\\],.;:].*")));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
    }

    @Test
    void movesThePrecedingTokenWhenClosingPunctuationWouldExceedTheWindow() {
        var versionId = UUID.randomUUID();
        var text = "alpha beta gamma delta epsilon，zeta eta theta iota kappa。".repeat(30);
        var block = block(versionId, BlockType.CODE, 0, text, List.of("Boundary"), Map.of());
        var policy = new ChunkPolicy(48, 56, 6, 8, 9, 0, "full-window-closing-boundary-test");

        var children = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() > 2);
        assertTrue(children.stream().allMatch(chunk ->
                !chunk.text().matches("^[，。；：、！？,.;:!?)}\\]】》].*")));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
    }

    @Test
    void keepsALeadingClosingLineWithItsPrecedingNaturalUnit() {
        var versionId = UUID.randomUUID();
        var text = ("FE 需要修改\nquery_port\n，BE 需要修改\nheartbeat_service_port\n，避免误导流。\n").repeat(24);
        var block = block(versionId, BlockType.PARAGRAPH, 0, text, List.of("Cluster operation"), Map.of());
        var policy = new ChunkPolicy(80, 100, 10, 18, 24, 0, "closing-line-boundary-test");

        var children = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() > 2);
        assertTrue(children.stream().allMatch(chunk ->
                !chunk.text().matches("^[，。；：、！？,.;:!?)}\\]】》].*")));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
    }

    @Test
    void recognizesUnicodeClosingPunctuationAtNaturalBoundaries() {
        var versionId = UUID.randomUUID();
        var text = ("（InitConfiguration\n或 JoinConfiguration\n）的配置需要保持一致。\n").repeat(24);
        var block = block(versionId, BlockType.PARAGRAPH, 0, text, List.of("Kubeadm"), Map.of());
        var policy = new ChunkPolicy(80, 100, 10, 18, 24, 0, "unicode-closing-boundary-test");

        var children = new AdaptiveParentChildChunker().chunk(versionId, List.of(block), policy).stream()
                .filter(chunk -> chunk.type() == ChunkType.CHILD).toList();

        assertTrue(children.size() > 2);
        assertTrue(children.stream().allMatch(chunk -> !chunk.text().startsWith("）")));
        assertTrue(children.stream().allMatch(chunk -> chunk.estimatedTokens() <= policy.childMaxTokens()));
    }

    private static DocumentBlock block(
            UUID versionId,
            BlockType type,
            int order,
            String text,
            List<String> headingPath,
            Map<String, Object> attributes
    ) {
        return new DocumentBlock(UUID.randomUUID(), versionId, type, order, text, 1, headingPath,
                0, text.length(), OffsetUnit.UTF16_CODE_UNIT, "hash-" + order, attributes);
    }
}
