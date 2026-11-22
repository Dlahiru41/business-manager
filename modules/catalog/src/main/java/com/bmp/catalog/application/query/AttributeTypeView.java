package com.bmp.catalog.application.query;

import com.bmp.catalog.domain.model.AttributeType;
import java.util.List;
import java.util.UUID;

/** An attribute type with its values, in display order. */
public record AttributeTypeView(UUID id, String code, String name, int sort, List<Value> values) {

    public AttributeTypeView {
        values = List.copyOf(values);
    }

    public static AttributeTypeView of(AttributeType attribute) {
        return new AttributeTypeView(
                attribute.id(),
                attribute.code(),
                attribute.name(),
                attribute.sort(),
                attribute.values().stream()
                        .map(v -> new Value(v.id(), v.code(), v.value(), v.sort()))
                        .toList());
    }

    /** One value. */
    public record Value(UUID id, String code, String value, int sort) {}
}
