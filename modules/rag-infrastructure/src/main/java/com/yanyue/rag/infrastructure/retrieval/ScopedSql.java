package com.yanyue.rag.infrastructure.retrieval;

import java.util.List;

public record ScopedSql(String predicate, List<Object> parameters) {
}
