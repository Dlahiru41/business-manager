package com.bmp.catalog.domain.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A new attribute value.
 *
 * @param code e.g. {@code GOLDEN_YELLOW}; derived from the value when absent
 * @param value e.g. {@code Golden Yellow}
 * @param sort display position; the next position when absent
 */
public record AttributeValueDraft(
        @Nullable String code, String value, @Nullable Integer sort) {

    public AttributeValueDraft {
        Objects.requireNonNull(value, "value");
    }
}
