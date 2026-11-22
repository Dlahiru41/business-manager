package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.model.TransformationDefinition;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to TransformationDefinition aggregates, used by the catalog store adapters. */
interface SpringDataDefinitions extends JpaRepository<TransformationDefinition, UUID> {}
