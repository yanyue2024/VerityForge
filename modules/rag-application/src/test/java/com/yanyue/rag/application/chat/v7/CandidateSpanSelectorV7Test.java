package com.yanyue.rag.application.chat.v7;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yanyue.rag.domain.chunking.v4.ChunkSourceMapBuilder;
import com.yanyue.rag.domain.chunking.v4.PageRange;
import com.yanyue.rag.domain.chunking.v4.ParentContext;
import com.yanyue.rag.domain.chunking.v4.SourceBlockSlice;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateSpanSelectorV7Test {
    private final CandidateSpanSelectorV7 selector = new CandidateSpanSelectorV7();

    @Test
    void allocatesOneSpanPerParentBeforeTakingSecondSpans() {
        var contexts = new ArrayList<ParentContext>();
        for (int index = 0; index < 6; index++) contexts.add(parent(index, 6 - index));

        var selected = selector.select(contexts, "核心内容", 8);

        assertEquals(8, selected.size());
        assertEquals(6, selected.stream().limit(6).map(value -> value.parentChunkId()).distinct().count());
        assertEquals(contexts.stream().map(ParentContext::parentChunkId).collect(java.util.stream.Collectors.toSet()),
                selected.stream().limit(6).map(value -> value.parentChunkId())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void respectsTheGlobalSpanLimit() {
        var contexts = List.of(parent(1, 2), parent(2, 1));

        var selected = selector.select(contexts, "核心内容", 3);

        assertEquals(3, selected.size());
        assertEquals(2, selected.stream().limit(2).map(value -> value.parentChunkId()).distinct().count());
    }

    private ParentContext parent(int index, double score) {
        var texts = List.of("父块" + index + "核心内容。", "父块" + index + "补充内容。");
        var slices = texts.stream().map(text -> new SourceBlockSlice(UUID.randomUUID(), text,
                0, text.length(), null, null, null, null)).toList();
        var parentId = UUID.randomUUID();
        var mapped = new ChunkSourceMapBuilder().build(parentId, slices);
        return new ParentContext(parentId, UUID.randomUUID(), UUID.randomUUID(), List.of("测试"),
                PageRange.unknown(), mapped.text(), List.of(), List.of(), mapped.sourceMap(), score);
    }
}
