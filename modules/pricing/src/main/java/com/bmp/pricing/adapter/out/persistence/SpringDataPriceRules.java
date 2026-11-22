package com.bmp.pricing.adapter.out.persistence;

import com.bmp.pricing.domain.model.PriceRule;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to PriceRule aggregates, used by the pricing store adapters. */
interface SpringDataPriceRules extends JpaRepository<PriceRule, UUID> {}
