package com.yanyue.rag.domain.chunking;

public enum SourceMapFailureReason {
    NONE,
    SOURCE_BLOCK_MISSING,
    TEXT_MISMATCH,
    AMBIGUOUS_MATCH,
    CROSSES_SOURCE_SEGMENTS,
    INVALID_RANGE
}
