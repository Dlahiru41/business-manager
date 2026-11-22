package com.bmp.pricing.application.query;

import com.bmp.pricing.domain.model.TaxRate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A tax rate as the API shows it. */
public record TaxRateView(
        UUID id,
        UUID taxCategoryId,
        String code,
        String name,
        BigDecimal ratePct,
        boolean compound,
        LocalDate validFrom,
        @Nullable LocalDate validTo,
        long version,
        Instant createdAt) {

    public static TaxRateView of(TaxRate rate) {
        return new TaxRateView(
                rate.id(),
                rate.taxCategoryId(),
                rate.code(),
                rate.name(),
                rate.ratePct(),
                rate.compound(),
                rate.validFrom(),
                rate.validTo(),
                rate.version(),
                rate.createdAt());
    }
}
