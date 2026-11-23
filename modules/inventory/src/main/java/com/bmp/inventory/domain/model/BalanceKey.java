package com.bmp.inventory.domain.model;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a stock balance (INV-01): a variant in a location, a stock state and a batch ({@link #NO_BATCH} when
 * none). Keys sort in the order balance rows are locked, so concurrent postings never deadlock (06 §7).
 */
public record BalanceKey(UUID variantId, UUID locationId, String stockState, UUID batchId)
        implements Comparable<BalanceKey> {

    /** The batch of stock that is not kept by batch. */
    public static final UUID NO_BATCH = new UUID(0L, 0L);

    private static final Comparator<BalanceKey> LOCK_ORDER = Comparator.comparing(BalanceKey::variantId)
            .thenComparing(BalanceKey::locationId)
            .thenComparing(BalanceKey::stockState)
            .thenComparing(BalanceKey::batchId);

    public BalanceKey {
        Objects.requireNonNull(variantId, "variantId");
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(stockState, "stockState");
        Objects.requireNonNull(batchId, "batchId");
    }

    public static BalanceKey of(UUID variantId, UUID locationId, String stockState) {
        return new BalanceKey(variantId, locationId, stockState, NO_BATCH);
    }

    public boolean hasBatch() {
        return !batchId.equals(NO_BATCH);
    }

    public BalanceKey withBatch(UUID batch) {
        return new BalanceKey(variantId, locationId, stockState, batch);
    }

    @Override
    public int compareTo(BalanceKey other) {
        return LOCK_ORDER.compare(this, other);
    }
}
