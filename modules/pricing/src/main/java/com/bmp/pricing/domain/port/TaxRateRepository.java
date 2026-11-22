package com.bmp.pricing.domain.port;

import com.bmp.pricing.domain.model.TaxRate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Tax rates of the current tenant. */
public interface TaxRateRepository {

    Optional<TaxRate> findRate(UUID id);

    List<TaxRate> findByCategories(Collection<UUID> taxCategoryIds);

    /** A keyset page ordered by id, optionally of one category. */
    List<TaxRate> findPage(@Nullable UUID taxCategoryId, @Nullable UUID afterId, int limit);

    TaxRate saveAndFlush(TaxRate rate);
}
