package com.bmp.pricing.application.query;

import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.service.PriceSchedule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One change of a list version, as stored in its change summary and shown by the API (PRC-03). */
public record PriceChangeView(
        String change,
        UUID priceId,
        UUID variantId,
        UUID packagingUnitId,
        BigDecimal minQty,
        Instant validFrom,
        @Nullable Instant validTo,
        BigDecimal amount,
        @Nullable BigDecimal previousAmount,
        @Nullable Instant previousValidTo) {

    public static PriceChangeView of(PriceSchedule.Change change) {
        Price price = change.price();
        return new PriceChangeView(
                change.kind().name(),
                price.id(),
                price.variantId(),
                price.packagingUnitId(),
                price.minQty(),
                price.validFrom(),
                price.validTo(),
                price.amount(),
                change.previousAmount(),
                change.previousValidTo());
    }
}
