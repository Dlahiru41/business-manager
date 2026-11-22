package com.bmp.catalog.application.query;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** The catalog at a version, or what changed since an earlier version (till caching, CAT-13). */
public record SnapshotView(
        long version,
        @Nullable Long sinceVersion,
        boolean full,
        List<UnitView> units,
        List<ProductView> products,
        List<VariantView> variants) {

    public SnapshotView {
        units = List.copyOf(units);
        products = List.copyOf(products);
        variants = List.copyOf(variants);
    }
}
