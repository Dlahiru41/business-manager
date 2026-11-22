package com.bmp.catalog.domain.model;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A change to the values of an attribute type: with an {@code id} it changes that value, without one it adds a value.
 *
 * @param id the value to change, or {@code null} to add one
 * @param code the new value's code (adding only; codes never change)
 * @param value the new display value
 * @param sort the new display position
 */
public record AttributeValueChange(
        @Nullable UUID id,
        @Nullable String code,
        @Nullable String value,
        @Nullable Integer sort) {}
