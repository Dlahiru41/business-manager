package com.bmp.procurement.api.events;

import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A line of a posted goods receipt (PRO-03).
 *
 * @param qty in the packaging unit; negative on a return to the supplier
 * @param qtyBase {@code qty × qtyInBase}
 * @param unitCost per packaging unit
 * @param unitCostBase {@code unitCost / qtyInBase}, scale 6
 * @param lineTotal {@code qty × unitCost}, rounded per the tenant policy
 */
public record GoodsReceivedLine(
        UUID lineId,
        @Nullable UUID purchaseOrderLineId,
        VariantId variantId,
        PackagingUnitId packagingUnitId,
        BigDecimal qty,
        BigDecimal qtyBase,
        String stockState,
        BigDecimal unitCost,
        BigDecimal unitCostBase,
        BigDecimal lineTotal,
        @Nullable String batchCode,
        @Nullable LocalDate expiresOn) {}
