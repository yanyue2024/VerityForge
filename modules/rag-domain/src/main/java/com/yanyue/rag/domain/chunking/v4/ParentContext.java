package com.yanyue.rag.domain.chunking.v4;

import java.util.List;
import java.util.UUID;

public record ParentContext(
        UUID parentChunkId,
        UUID documentId,
        UUID documentVersionId,
        List<String> titlePath,
        PageRange pageRange,
        String text,
        List<ChildAnchor> childAnchors,
        List<QueryProvenance> queryProvenance,
        ChunkSourceMap sourceMap,
        double retrievalScore
) {
    public ParentContext {
        titlePath = titlePath == null ? List.of() : List.copyOf(titlePath);
        pageRange = pageRange == null ? PageRange.unknown() : pageRange;
        text = text == null ? "" : text;
        childAnchors = childAnchors == null ? List.of() : List.copyOf(childAnchors);
        queryProvenance = queryProvenance == null ? List.of() : List.copyOf(queryProvenance);
        if (sourceMap == null || !parentChunkId.equals(sourceMap.chunkId())) {
            throw new IllegalArgumentException("ParentContext 与 Source Map 的父块必须一致");
        }
        if (!Double.isFinite(retrievalScore)) throw new IllegalArgumentException("检索分数必须是有限数值");
        for (var anchor : childAnchors) {
            if (anchor.parentLocalEnd() > text.length()) {
                throw new IllegalArgumentException("子块锚点超出父块文本范围");
            }
        }
    }

    public boolean evidenceMayBeHidden() {
        return sourceMap.status() == SourceMapStatus.UNMAPPABLE;
    }
}
