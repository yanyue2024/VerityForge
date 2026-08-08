package com.yanyue.rag.infrastructure.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.chunking.v4.ChildAnchor;
import com.yanyue.rag.domain.chunking.v4.ChunkSourceMap;
import com.yanyue.rag.domain.chunking.v4.ChunkSourceSegment;
import com.yanyue.rag.domain.chunking.v4.HistoricalChunkSourceMapReconstructor;
import com.yanyue.rag.domain.chunking.v4.HistoricalSourceBlock;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.PageRange;
import com.yanyue.rag.domain.chunking.v4.ParentContext;
import com.yanyue.rag.domain.chunking.v4.QueryProvenance;
import com.yanyue.rag.domain.chunking.v4.SourceMapFailureReason;
import com.yanyue.rag.domain.port.AgenticV4ContextPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class JooqAgenticV4ContextAdapter implements AgenticV4ContextPort {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final RetrievalScopeSqlBuilder scopeBuilder = new RetrievalScopeSqlBuilder();
    private final HistoricalChunkSourceMapReconstructor sourceMapReconstructor =
            new HistoricalChunkSourceMapReconstructor();

    public JooqAgenticV4ContextAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public ParentLoadResult loadParentContexts(
            List<ChildCandidate> children,
            RetrievalScope scope,
            int maximumParents
    ) {
        return loadParentContexts(children, scope, maximumParents, Duration.ofSeconds(30));
    }

    @Override
    public ParentLoadResult loadParentContexts(
            List<ChildCandidate> children,
            RetrievalScope scope,
            int maximumParents,
            Duration timeout
    ) {
        if (children == null || children.isEmpty() || maximumParents <= 0) {
            return new ParentLoadResult(List.of(), false, 0);
        }
        var grouped = new LinkedHashMap<ParentKey, List<ChildCandidate>>();
        children.stream()
                .filter(candidate -> candidate.hit().parentChunkId() != null)
                .sorted(Comparator.comparingDouble((ChildCandidate value) -> value.hit().score()).reversed())
                .forEach(candidate -> grouped.computeIfAbsent(
                        new ParentKey(candidate.hit().documentVersionId(), candidate.hit().parentChunkId()),
                        ignored -> new ArrayList<>()).add(candidate));

        var contexts = new ArrayList<ParentContext>();
        boolean hidden = false;
        int reads = 0;
        for (var entry : grouped.entrySet()) {
            if (reads >= maximumParents) break;
            reads++;
            var loaded = loadOne(entry.getKey(), entry.getValue(), scope, timeout);
            if (loaded == null) continue;
            contexts.add(loaded);
            hidden |= loaded.evidenceMayBeHidden();
        }
        return new ParentLoadResult(contexts, hidden, reads);
    }

    private ParentContext loadOne(
            ParentKey key,
            List<ChildCandidate> children,
            RetrievalScope scope,
            Duration timeout
    ) {
        var scoped = scopeBuilder.build(scope);
        var sql = """
                SELECT p.id AS parent_id, p.chunk_text AS parent_text, p.source_block_ids,
                       d.id AS document_id, dv.id AS document_version_id, d.title AS document_title
                FROM chunk c
                JOIN chunk p ON p.id = c.parent_chunk_id AND p.document_version_id = c.document_version_id
                JOIN document_version dv ON dv.id = c.document_version_id
                JOIN document d ON d.id = dv.document_id
                JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
                WHERE c.id = ? AND p.id = ? AND p.chunk_type = 'PARENT' AND p.enabled = true
                  AND
                """ + scoped.predicate();
        var parameters = new ArrayList<Object>();
        parameters.add(children.getFirst().hit().chunkId());
        parameters.add(key.parentChunkId());
        parameters.addAll(scoped.parameters());
        var parent = dsl.resultQuery(sql, parameters.toArray())
                .queryTimeout(timeoutSeconds(timeout)).fetchOptional();
        if (parent.isEmpty()) return null;

        var record = parent.get();
        var parentText = record.get("parent_text", String.class);
        var sourceBlockIds = List.of(record.get("source_block_ids", UUID[].class));
        var blocks = loadBlocks(key.documentVersionId(), sourceBlockIds, timeout);
        var sourceMap = loadSourceMap(key.parentChunkId(), timeout);
        if (sourceMap.status() == com.yanyue.rag.domain.chunking.v4.SourceMapStatus.UNMAPPABLE) {
            sourceMap = reconstructSourceMap(key.parentChunkId(), parentText, blocks);
        }
        var anchors = childAnchors(parentText, children);
        var provenance = children.stream().map(candidate -> new QueryProvenance(
                candidate.queryId().toString(), candidate.hit().chunkId(), candidate.hit().score())).toList();
        var titlePath = blocks.stream().filter(block -> !block.headingPath().isEmpty())
                .map(Block::headingPath).findFirst().orElse(List.of());
        var pages = blocks.stream().map(Block::pageNumber).filter(java.util.Objects::nonNull).toList();
        var pageRange = pages.isEmpty() ? PageRange.unknown()
                : new PageRange(pages.stream().min(Integer::compareTo).orElseThrow(),
                        pages.stream().max(Integer::compareTo).orElseThrow());
        var score = children.stream().mapToDouble(value -> value.hit().score()).max().orElse(0);
        return new ParentContext(key.parentChunkId(), record.get("document_id", UUID.class),
                key.documentVersionId(), titlePath, pageRange, parentText, anchors, provenance, sourceMap, score);
    }

    private List<Block> loadBlocks(UUID versionId, List<UUID> ids, Duration timeout) {
        if (ids.isEmpty()) return List.of();
        return dsl.resultQuery("""
                SELECT id, block_text, page_number, heading_path
                FROM document_block
                WHERE document_version_id = ? AND id = ANY(?::uuid[])
                ORDER BY order_index
                """, versionId, ids.toArray(UUID[]::new)).queryTimeout(timeoutSeconds(timeout)).fetch()
                .map(record -> new Block(
                record.get("id", UUID.class), record.get("block_text", String.class),
                record.get("page_number", Integer.class), readStrings(record.get("heading_path", JSONB.class))));
    }

    private ChunkSourceMap loadSourceMap(UUID chunkId, Duration timeout) {
        var status = dsl.resultQuery("SELECT source_mapping_status FROM chunk WHERE id = ?", chunkId)
                .queryTimeout(timeoutSeconds(timeout)).fetchOptional()
                .map(record -> record.get("source_mapping_status", String.class)).orElse("UNMAPPABLE");
        if (!"MAPPED".equals(status)) {
            return ChunkSourceMap.unmappable(chunkId, SourceMapFailureReason.SOURCE_BLOCK_MISSING);
        }
        var segments = dsl.resultQuery("""
                SELECT segment_order, chunk_local_start, chunk_local_end, document_block_id,
                       block_local_start, block_local_end, document_source_start, document_source_end,
                       document_offset_unit,
                       db.page_number
                FROM chunk_source_segment
                JOIN document_block db ON db.id = chunk_source_segment.document_block_id
                WHERE chunk_id = ?
                ORDER BY segment_order
                """, chunkId).queryTimeout(timeoutSeconds(timeout)).fetch().map(record -> new ChunkSourceSegment(
                record.get("segment_order", Integer.class),
                record.get("chunk_local_start", Integer.class),
                record.get("chunk_local_end", Integer.class), OffsetUnit.UTF16_CODE_UNIT,
                record.get("document_block_id", UUID.class),
                record.get("block_local_start", Integer.class),
                record.get("block_local_end", Integer.class), OffsetUnit.UTF16_CODE_UNIT,
                record.get("document_source_start", Integer.class),
                record.get("document_source_end", Integer.class),
                offsetUnit(record.get("document_offset_unit", String.class)),
                record.get("page_number", Integer.class)));
        return segments.isEmpty()
                ? ChunkSourceMap.unmappable(chunkId, SourceMapFailureReason.SOURCE_BLOCK_MISSING)
                : ChunkSourceMap.mapped(chunkId, segments);
    }

    private OffsetUnit offsetUnit(String value) {
        return value == null ? null : OffsetUnit.valueOf(value);
    }

    private int timeoutSeconds(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("父块读取超时必须为正数");
        }
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, timeout.toSeconds()));
    }

    private ChunkSourceMap reconstructSourceMap(UUID chunkId, String parentText, List<Block> blocks) {
        return sourceMapReconstructor.reconstruct(chunkId, parentText, blocks.stream()
                .map(block -> new HistoricalSourceBlock(
                        block.id(), block.text(), null, null, null, block.pageNumber()))
                .toList());
    }

    private List<ChildAnchor> childAnchors(String parentText, List<ChildCandidate> children) {
        var result = new ArrayList<ChildAnchor>();
        for (var child : children) {
            var text = child.hit().text();
            int start = parentText.indexOf(text);
            if (start < 0 || parentText.indexOf(text, start + 1) >= 0) continue;
            result.add(new ChildAnchor(child.hit().chunkId(), start, start + text.length()));
        }
        return List.copyOf(result);
    }

    private List<String> readStrings(JSONB value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value.data(), STRING_LIST);
        } catch (Exception exception) {
            throw new IllegalStateException("文档标题路径 JSON 无法解析", exception);
        }
    }

    private record ParentKey(UUID documentVersionId, UUID parentChunkId) { }

    private record Block(UUID id, String text, Integer pageNumber, List<String> headingPath) { }

}
