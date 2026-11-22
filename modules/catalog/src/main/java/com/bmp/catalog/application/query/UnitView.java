package com.bmp.catalog.application.query;

import com.bmp.catalog.domain.model.UnitOfMeasure;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A unit of measure as the API shows it. */
public record UnitView(
        UUID id,
        String code,
        String name,
        String dimension,
        @Nullable BigDecimal toSiFactor,
        boolean standard) {

    public static UnitView of(UnitOfMeasure unit) {
        return new UnitView(
                unit.id(), unit.code(), unit.name(), unit.dimension().name(), unit.toSiFactor(), unit.isStandard());
    }
}
