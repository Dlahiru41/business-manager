package com.bmp.pricing.adapter.out.persistence;

import com.bmp.pricing.domain.model.TaxCategory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to TaxCategory aggregates, used by the pricing store adapters. */
interface SpringDataTaxCategories extends JpaRepository<TaxCategory, UUID> {}
