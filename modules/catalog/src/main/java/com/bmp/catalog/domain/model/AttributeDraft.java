package com.bmp.catalog.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * A new attribute type with its values.
 *
 * @param code e.g. {@code COLOUR}; stored upper-case
 * @param name e.g. {@code Colour}
 * @param sort position among the product's attributes
 * @param values at least one
 */
public record AttributeDraft(String code, String name, int sort, List<AttributeValueDraft> values) {

    public AttributeDraft {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        values = List.copyOf(values);
    }
}
