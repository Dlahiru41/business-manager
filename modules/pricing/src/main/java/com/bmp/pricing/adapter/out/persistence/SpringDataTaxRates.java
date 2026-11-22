package com.bmp.pricing.adapter.out.persistence;

import com.bmp.pricing.domain.model.TaxRate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to TaxRate aggregates, used by the pricing store adapters. */
interface SpringDataTaxRates extends JpaRepository<TaxRate, UUID> {}
