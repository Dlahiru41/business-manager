package com.bmp.pricing.adapter.out.persistence;

import com.bmp.pricing.domain.model.Price;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to Price aggregates, used by the pricing store adapters. */
interface SpringDataPrices extends JpaRepository<Price, UUID> {}
