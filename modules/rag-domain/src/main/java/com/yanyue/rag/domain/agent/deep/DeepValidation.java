package com.yanyue.rag.domain.agent.deep;

import java.util.Collection;
import java.util.Objects;

final class DeepValidation {
    private DeepValidation() {
    }

    static <T> T required(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String requiredText(String value, String field, int maximumLength) {
        value = requiredText(value, field).strip();
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length " + maximumLength);
        }
        if (value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && !Character.isWhitespace(codePoint))) {
            throw new IllegalArgumentException(field + " contains an unsupported control character");
        }
        return value;
    }

    static void sizeBetween(Collection<?> values, int minimum, int maximum, String field) {
        required(values, field);
        if (values.size() < minimum || values.size() > maximum) {
            throw new IllegalArgumentException(field + " size must be between " + minimum + " and " + maximum);
        }
    }

    static void positiveAtMost(int value, int maximum, String field) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(field + " must be between 1 and " + maximum);
        }
    }
}
