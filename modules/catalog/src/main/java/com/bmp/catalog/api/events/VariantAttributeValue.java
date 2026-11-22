package com.bmp.catalog.api.events;

import java.util.Objects;

/**
 * One attribute value of a variant, e.g. {@code COLOUR = RED (Red)}.
 *
 * @param attributeCode the attribute type code
 * @param valueCode the value code
 * @param value the display value
 */
public record VariantAttributeValue(String attributeCode, String valueCode, String value) {

    public VariantAttributeValue {
        Objects.requireNonNull(attributeCode, "attributeCode");
        Objects.requireNonNull(valueCode, "valueCode");
        Objects.requireNonNull(value, "value");
    }
}
