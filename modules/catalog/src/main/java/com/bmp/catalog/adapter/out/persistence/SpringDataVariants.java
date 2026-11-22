package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.model.Variant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to variant aggregates, used by the catalog store adapters. */
interface SpringDataVariants extends JpaRepository<Variant, UUID> {}
