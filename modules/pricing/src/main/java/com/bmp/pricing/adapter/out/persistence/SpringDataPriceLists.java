package com.bmp.pricing.adapter.out.persistence;

import com.bmp.pricing.domain.model.PriceList;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to PriceList aggregates, used by the pricing store adapters. */
interface SpringDataPriceLists extends JpaRepository<PriceList, UUID> {}
