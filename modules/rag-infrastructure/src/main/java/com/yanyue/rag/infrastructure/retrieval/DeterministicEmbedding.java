package com.yanyue.rag.infrastructure.retrieval;

import java.util.Locale;

public final class DeterministicEmbedding {
    public static final int DIMENSION = 384;

    public float[] embed(String text) {
        var values = new float[DIMENSION];
        var normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        var codePoints = normalized.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            add(values, Integer.toString(codePoints[index]));
            if (index + 1 < codePoints.length) add(values, codePoints[index] + ":" + codePoints[index + 1]);
        }
        double norm = 0;
        for (float value : values) norm += value * value;
        if (norm == 0) return values;
        var divisor = (float) Math.sqrt(norm);
        for (int index = 0; index < values.length; index++) values[index] /= divisor;
        return values;
    }

    public String toPgVector(float[] values) {
        var builder = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) builder.append(',');
            builder.append(Float.toString(values[index]));
        }
        return builder.append(']').toString();
    }

    private void add(float[] values, String token) {
        int hash = token.hashCode();
        int position = Math.floorMod(hash, values.length);
        values[position] += (hash & 1) == 0 ? 1f : -1f;
    }
}
