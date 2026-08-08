package com.yanyue.rag.domain.agent.v4;

import java.util.Collection;
import java.util.Objects;

final class V4Validation {
    private V4Validation() {
    }

    static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String requiredText(String value, String field, int maximumLength) {
        value = requiredText(value, field);
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length " + maximumLength);
        }
        return value;
    }

    static <T> T required(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    static void sizeBetween(Collection<?> values, int minimum, int maximum, String field) {
        if (values.size() < minimum || values.size() > maximum) {
            throw new IllegalArgumentException(field + " size must be between " + minimum + " and " + maximum);
        }
    }
}
