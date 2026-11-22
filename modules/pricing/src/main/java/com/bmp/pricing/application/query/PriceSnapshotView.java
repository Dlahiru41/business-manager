package com.bmp.pricing.application.query;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A list's prices at its version, or the prices changed since an earlier version (PRC-09). */
public record PriceSnapshotView(
        PriceListView list, int version, @Nullable Integer sinceVersion, boolean full, List<PriceView> prices) {

    public PriceSnapshotView {
        prices = List.copyOf(prices);
    }
}
