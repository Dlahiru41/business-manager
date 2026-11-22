package com.bmp.catalog.domain.port;

import com.bmp.catalog.domain.model.DefinitionStatus;
import com.bmp.catalog.domain.model.TransformationDefinition;
import com.bmp.catalog.domain.model.TransformationKind;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Transformation definitions of the current tenant. */
public interface TransformationDefinitionRepository {

    Optional<TransformationDefinition> findById(UUID id);

    TransformationDefinition save(TransformationDefinition definition);

    /** Saves and flushes, so the returned aggregate carries its new version. */
    TransformationDefinition saveAndFlush(TransformationDefinition definition);

    /** Definitions with a line sized by one of these packaging units (CAT-14). */
    List<TransformationDefinition> findUsingPackagingUnits(Collection<UUID> packagingUnitIds);

    /** A keyset page ordered by code then id. */
    List<TransformationDefinition> findPage(
            @Nullable TransformationKind kind,
            @Nullable DefinitionStatus status,
            @Nullable UUID variantId,
            @Nullable PageKey after,
            int limit);
}
