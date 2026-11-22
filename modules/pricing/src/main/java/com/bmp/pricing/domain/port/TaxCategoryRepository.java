package com.bmp.pricing.domain.port;

import com.bmp.pricing.domain.model.TaxCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Tax categories of the current tenant. */
public interface TaxCategoryRepository {

    Optional<TaxCategory> findById(UUID id);

    /** A keyset page by code, after the category with this code. */
    List<TaxCategory> findPage(@Nullable String afterCode, int limit);

    TaxCategory saveAndFlush(TaxCategory category);
}
