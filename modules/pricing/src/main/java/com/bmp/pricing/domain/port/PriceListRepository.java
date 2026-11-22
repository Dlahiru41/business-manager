package com.bmp.pricing.domain.port;

import com.bmp.pricing.domain.model.PriceList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Price lists of the current tenant (a handful per tenant). */
public interface PriceListRepository {

    Optional<PriceList> findById(UUID id);

    /** Loads the list and locks its row until the transaction ends: price upserts of one list run one at a time. */
    Optional<PriceList> lockById(UUID id);

    Optional<PriceList> findByCode(String code);

    /** Every list, by ascending priority then code. */
    List<PriceList> findAll();

    PriceList save(PriceList list);

    /** Saves and flushes, so the returned aggregate carries its new version. */
    PriceList saveAndFlush(PriceList list);
}
