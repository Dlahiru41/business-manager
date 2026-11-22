package com.bmp.catalog.domain.port;

import com.bmp.catalog.domain.model.CatalogStatus;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.ProductTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Products of the current tenant. */
public interface ProductRepository {

    Optional<Product> findById(UUID id);

    Product save(Product product);

    /** Saves and flushes, so the returned aggregate carries its new version. */
    Product saveAndFlush(Product product);

    /** A keyset page ordered by code then id. */
    List<Product> findPage(Filter filter, @Nullable PageKey after, int limit);

    /** Products changed after a catalog version (all when {@code revision} is 0), ordered by revision. */
    List<Product> findChangedSince(long revision);

    /**
     * Product list filters; {@code null} matches everything.
     *
     * @param q case-insensitive part of the code or name
     * @param categoryId only products of this category
     * @param status only products with this status
     * @param template only products of this template
     */
    record Filter(
            @Nullable String q,
            @Nullable UUID categoryId,
            @Nullable CatalogStatus status,
            @Nullable ProductTemplate template) {}
}
