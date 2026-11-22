package com.bmp.tenant.domain.model;

import com.bmp.shared.id.TypedId;
import java.util.Objects;
import java.util.UUID;

/** Identifies a receipt template. */
public record ReceiptTemplateId(UUID value) implements TypedId {

    public ReceiptTemplateId {
        Objects.requireNonNull(value, "value");
    }

    public static ReceiptTemplateId of(UUID value) {
        return new ReceiptTemplateId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
