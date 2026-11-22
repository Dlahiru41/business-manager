package com.bmp.catalog.domain.port;

import com.bmp.catalog.domain.model.Dimension;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Units of measure the current tenant sees: the platform standard units and its own (CAT-01). */
public interface UnitRepository {

    /** Every visible unit, standard units first, then by code. */
    List<UnitOfMeasure> findAll();

    Optional<UnitOfMeasure> findById(UUID id);

    /** Case-insensitive lookup among the visible units. */
    Optional<UnitOfMeasure> findByCode(String code);

    /**
     * A keyset page, standard units first, then by code.
     *
     * @param after the sort key ({@code 0:<code>} for standard, {@code 1:<code>} for own units) and id of the last
     *     unit of the previous page
     */
    List<UnitOfMeasure> findPage(@Nullable Dimension dimension, @Nullable PageKey after, int limit);

    void insert(UnitOfMeasure unit);
}
