package com.bmp.inventory.adapter.in.web;

import com.bmp.inventory.adapter.in.web.generated.model.AdjustmentLine;
import com.bmp.inventory.application.command.StockOperationHandlers;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request DTOs to command values. */
final class InventoryRequests {

    private InventoryRequests() {}

    static @Nullable Instant instant(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    static @Nullable BigDecimal decimal(@Nullable String value) {
        return value == null ? null : new BigDecimal(value);
    }

    static List<StockOperationHandlers.ItemQty> lines(List<AdjustmentLine> lines) {
        return lines.stream()
                .map(l -> new StockOperationHandlers.ItemQty(
                        l.getVariantId(),
                        l.getPackagingUnitId(),
                        l.getStockState(),
                        l.getBatchId(),
                        new BigDecimal(l.getQty())))
                .toList();
    }
}
