package com.bmp.pricing.domain.port;

import com.bmp.pricing.domain.model.PriceRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Price rules of the current tenant. */
public interface PriceRuleRepository {

    Optional<PriceRule> findById(UUID id);

    /** Every rule, by ascending priority then code. */
    List<PriceRule> findAll();

    PriceRule save(PriceRule rule);

    /** Saves and flushes, so the returned aggregate carries its new version. */
    PriceRule saveAndFlush(PriceRule rule);
}
