package com.bmp.inventory.application;

import com.bmp.inventory.api.InventoryCommands;
import com.bmp.inventory.api.InventoryQueries;
import com.bmp.inventory.application.command.StockOperationHandlers;
import com.bmp.inventory.application.query.StockReadModel;
import com.bmp.inventory.domain.model.WastageReason;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link InventoryCommands} and {@link InventoryQueries} over the stock handlers and read model. */
@Component
class InventoryApiImpl implements InventoryCommands, InventoryQueries {

    /** More balances than a location of a shop holds; the read model pages, a till takes them all. */
    private static final int ALL = 100_000;

    private final StockOperationHandlers operations;
    private final StockReadModel stock;

    InventoryApiImpl(StockOperationHandlers operations, StockReadModel stock) {
        this.operations = operations;
        this.stock = stock;
    }

    @Override
    @Transactional
    public void recordWastage(Wastage w) {
        WastageReason reason;
        try {
            reason = WastageReason.valueOf(w.reason());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED, "Unknown wastage reason", Map.of("field", "reason"));
        }
        operations.adjust(
                w.locationId(),
                reason,
                w.note(),
                w.occurredAt(),
                w.lines().stream()
                        .map(l -> new StockOperationHandlers.ItemQty(
                                l.variantId(), l.packagingUnitId(), l.stockState(), null, l.qty()))
                        .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Available> available(UUID locationId) {
        Map<String, Available> byItem = new TreeMap<>();
        for (StockReadModel.BalanceRow row :
                stock.balances(new StockReadModel.BalanceFilter(null, locationId, null, false, false), null, ALL)) {
            byItem.merge(
                    row.variantId() + ":" + row.stockState(),
                    new Available(row.variantId(), row.stockState(), row.available()),
                    (a, b) -> new Available(
                            a.variantId(), a.stockState(), a.available().add(b.available())));
        }
        return List.copyOf(byItem.values());
    }
}
