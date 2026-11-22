package com.bmp.catalog.domain.port;

import com.bmp.catalog.domain.model.CatalogStatus;
import com.bmp.catalog.domain.model.Variant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Variants of the current tenant. */
public interface VariantRepository {

    Optional<Variant> findById(UUID id);

    List<Variant> findAllById(Collection<UUID> ids);

    /** The variant owning a packaging unit. */
    Optional<Variant> findByPackagingUnit(UUID packagingUnitId);

    Optional<Variant> findBySku(String sku);

    /** The variant one of whose packaging units carries the barcode (CAT-12). */
    Optional<Variant> findByBarcode(String barcode);

    /** Every variant of a product (at most 500), oldest first. */
    List<Variant> findByProduct(UUID productId);

    boolean existsByProduct(UUID productId);

    /** SKUs in use that start with the prefix, for de-duplication (CAT-06). */
    Set<String> skusStartingWith(String prefix);

    /** A keyset page ordered by SKU then id. */
    List<Variant> findPage(Filter filter, @Nullable PageKey after, int limit);

    /** Variants changed after a catalog version (all when {@code revision} is 0), ordered by revision. */
    List<Variant> findChangedSince(long revision);

    Variant save(Variant variant);

    /** Saves and flushes, so the returned aggregate carries its new version. */
    Variant saveAndFlush(Variant variant);

    /**
     * Variant list filters; {@code null} matches everything.
     *
     * @param sellable CAT-08: active with an active unit that can be sold
     * @param purchasable CAT-08: active with an active unit that can be bought
     * @param q case-insensitive part of the SKU or name
     * @param productId only variants of this product
     * @param status only variants with this status
     */
    record Filter(
            @Nullable Boolean sellable,
            @Nullable Boolean purchasable,
            @Nullable String q,
            @Nullable UUID productId,
            @Nullable CatalogStatus status) {}
}
