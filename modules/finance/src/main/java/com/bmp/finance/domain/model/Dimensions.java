package com.bmp.finance.domain.model;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Analysis dimensions of a journal line (data-model.md {@code journal_line.dimensions}). */
public record Dimensions(
        @Nullable UUID variantId,
        @Nullable UUID productId,
        @Nullable String channel,
        @Nullable UUID locationId,
        @Nullable UUID billId) {

    public static final Dimensions NONE = new Dimensions(null, null, null, null, null);

    public static Dimensions location(@Nullable UUID locationId) {
        return new Dimensions(null, null, null, locationId, null);
    }

    public static Dimensions bill(@Nullable UUID billId, @Nullable UUID locationId, @Nullable String channel) {
        return new Dimensions(null, null, channel, locationId, billId);
    }

    public Dimensions withVariant(@Nullable UUID variant) {
        return new Dimensions(variant, productId, channel, locationId, billId);
    }

    public boolean isEmpty() {
        return equals(NONE);
    }
}
