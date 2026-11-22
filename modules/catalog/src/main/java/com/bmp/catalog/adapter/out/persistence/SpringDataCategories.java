package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.model.Category;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to Category aggregates, used by the catalog store adapters. */
interface SpringDataCategories extends JpaRepository<Category, UUID> {}
