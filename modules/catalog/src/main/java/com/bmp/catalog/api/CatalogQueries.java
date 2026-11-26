package com.bmp.catalog.api;

import com.bmp.catalog.api.dto.PackagingUnitInfo;
import com.bmp.catalog.api.dto.VariantInfo;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import java.util.Optional;

/**
 * Read-only catalog questions other modules may ask (sync from phase 03). Prefer the catalog events and a local
 * replica when an answer is needed often; these calls read the catalog of the current tenant.
 */
public interface CatalogQueries {

    /** The variant with its packaging units, active or not. */
    Optional<VariantInfo> findVariant(VariantId variant);

    /** The packaging unit, active or not. */
    Optional<PackagingUnitInfo> findPackagingUnit(PackagingUnitId packagingUnit);

    /** The variant whose packaging unit carries the barcode (CAT-12). */
    Optional<VariantInfo> findByBarcode(String barcode);

    /** The tenant's catalog version: it grows with every catalog change. */
    long catalogVersion();

    /**
     * The whole catalog as a till caches it (SYN-02): a tree of records that serialises to catalog.yaml
     * {@code CatalogSnapshot}. Other modules pass it on and never look inside.
     */
    Object tillSnapshot();
}
