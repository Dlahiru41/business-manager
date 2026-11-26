package com.bmp.sales.api.events;

import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.util.UUID;

/** A returned quantity of a bill line (SAL-10); restocked lines go back to stock at their original cost. */
public record ReturnedLine(
        UUID billLineId,
        VariantId variantId,
        BigDecimal qty,
        BigDecimal qtyBase,
        String stockState,
        BigDecimal amount,
        BigDecimal tax,
        boolean restock) {}
