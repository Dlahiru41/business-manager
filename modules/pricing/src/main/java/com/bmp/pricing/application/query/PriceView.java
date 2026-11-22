package com.bmp.pricing.application.query;

import com.bmp.pricing.domain.model.Price;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A price as the API shows it, with its list currency. */
public record PriceView(
        UUID id,
        UUID priceListId,
        UUID variantId,
        UUID packagingUnitId,
        BigDecimal minQty,
        BigDecimal amount,
        String currency,
        Instant validFrom,
        @Nullable Instant validTo,
        int listVersion) {

    public static PriceView of(Price price, String currency) {
        return new PriceView(
                price.id(),
                price.priceListId(),
                price.variantId(),
                price.packagingUnitId(),
                price.minQty(),
                price.amount(),
                currency,
                price.validFrom(),
                price.validTo(),
                price.listVersion());
    }
}
