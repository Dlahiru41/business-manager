package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.model.Product;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to product aggregates, used by the catalog store adapters. */
interface SpringDataProducts extends JpaRepository<Product, UUID> {}
