package com.bmp.pricing.api.events;

import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One price changed in a list version.
 *
 * @param change CREATED, UPDATED or ENDED
 * @param priceId the price
 * @param variantId its variant
 * @param packagingUnitId its packaging unit
 * @param minQty its minimum quantity in that unit
 * @param amount its amount in the list currency
 * @param validFrom when it starts (inclusive)
 * @param validTo when it ends (exclusive), if it ends
 */
public record PriceChangeSnapshot(
        String change,
        UUID priceId,
        VariantId variantId,
        PackagingUnitId packagingUnitId,
        BigDecimal minQty,
        BigDecimal amount,
        Instant validFrom,
        @Nullable Instant validTo) {

    public PriceChangeSnapshot {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(priceId, "priceId");
        Objects.requireNonNull(amount, "amount");
    }
}
