package com.bmp.pricing.domain.model;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What price rule conditions look at for one basket line.
 *
 * @param channel the bill's channel
 * @param priceListId the list the line's price came from
 * @param customerGroupId the customer's group, if any
 * @param customerId the customer, if any
 * @param productId the variant's product
 * @param variantId the variant
 * @param packagingUnitId the unit sold
 * @param qtyBase the line quantity in base units
 */
public record RuleContext(
        SalesChannel channel,
        UUID priceListId,
        @Nullable UUID customerGroupId,
        @Nullable UUID customerId,
        UUID productId,
        UUID variantId,
        UUID packagingUnitId,
        BigDecimal qtyBase) {}
