package com.yanyue.rag.infrastructure.retrieval;

import java.util.List;

public final class PgVectorFormatter {
    private PgVectorFormatter() {
    }

    public static String format(List<Float> values) {
        var builder = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) builder.append(',');
            var value = values.get(index);
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding contains a non-finite value");
            }
            builder.append(Float.toString(value));
        }
        return builder.append(']').toString();
    }
}
