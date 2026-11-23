package com.bmp.inventory.application.query;

import com.bmp.inventory.domain.model.BalanceKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The read side of balances and the ledger (T02-07): pages, valuation and the reorder list, straight from SQL. */
public interface StockReadModel {

    /** Stock of products without a category is valued under this key. */
    UUID UNCATEGORISED = new UUID(0, 0);

    record BalanceFilter(
            @Nullable UUID variantId,
            @Nullable UUID locationId,
            @Nullable String stockState,
            boolean belowReorderOnly,
            boolean negativeOnly) {}

    /** A balance with what the API shows beside it; {@code batchKey} is {@link BalanceKey#NO_BATCH} without batch. */
    record BalanceRow(
            UUID variantId,
            UUID productId,
            String sku,
            UUID locationId,
            String stockState,
            UUID batchKey,
            @Nullable String batchCode,
            @Nullable LocalDate expiresOn,
            BigDecimal onHand,
            BigDecimal reserved,
            BigDecimal avgUnitCost,
            BigDecimal stockValue,
            @Nullable BigDecimal reorderLevel,
            boolean belowReorder,
            @Nullable Instant lastMovementAt) {

        public BigDecimal available() {
            return onHand.subtract(reserved);
        }

        public @Nullable UUID batchId() {
            return batchKey.equals(BalanceKey.NO_BATCH) ? null : batchKey;
        }

        public List<String> cursor() {
            return List.of(variantId.toString(), locationId.toString(), stockState, batchKey.toString());
        }
    }

    record MovementFilter(
            @Nullable UUID variantId,
            @Nullable UUID locationId,
            @Nullable String type,
            @Nullable Instant from,
            @Nullable Instant to) {}

    enum Grouping {
        VARIANT,
        PRODUCT,
        CATEGORY,
        LOCATION
    }

    record ValuationRow(UUID key, @Nullable String label, BigDecimal qtyBase, BigDecimal value) {}

    /** A variant at a location at or below its reorder level (INV-13, PRO-06). */
    record ReorderRow(
            UUID variantId,
            String sku,
            UUID locationId,
            BigDecimal available,
            BigDecimal reorderLevel,
            BigDecimal reorderQty,
            @Nullable UUID preferredSupplierId,
            @Nullable BigDecimal lastUnitCostBase) {}

    /** Balances in key order after the given cursor values. */
    List<BalanceRow> balances(BalanceFilter filter, @Nullable List<String> after, int limit);

    /** Movements newest first, before the given occurrence time and id. */
    List<MovementView> movements(MovementFilter filter, @Nullable Instant beforeAt, @Nullable UUID beforeId, int limit);

    /** Σ quantities and costs of the movements up to {@code asOf}, grouped. */
    List<ValuationRow> valuation(Instant asOf, Grouping groupBy, @Nullable UUID locationId);

    List<ReorderRow> reorderList(@Nullable UUID locationId);
}
