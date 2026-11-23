package com.bmp.inventory.application;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.inventory.domain.model.BatchInfo;
import com.bmp.inventory.domain.model.LocationRef;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.port.BatchStore;
import com.bmp.inventory.domain.port.LocationStore;
import com.bmp.inventory.domain.port.StockItemStore;
import com.bmp.shared.error.BusinessRuleViolation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Checks the replicas a request refers to: locations, variants and batches. */
@Component
public class StockReferences {

    private static final DateTimeFormatter BATCH_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LocationStore locations;
    private final StockItemStore items;
    private final BatchStore batches;

    public StockReferences(LocationStore locations, StockItemStore items, BatchStore batches) {
        this.locations = locations;
        this.items = items;
        this.batches = batches;
    }

    public LocationRef location(UUID locationId) {
        return locations
                .find(locationId)
                .filter(LocationRef::active)
                .orElseThrow(() -> new BusinessRuleViolation(
                        InventoryErrorCode.UNKNOWN_LOCATION, Map.of("locationId", locationId)));
    }

    /** An active variant inventory knows. */
    public StockItem item(UUID variantId) {
        StockItem item = knownItem(variantId);
        if (!item.active()) {
            throw new BusinessRuleViolation(InventoryErrorCode.STOCK_ITEM_INACTIVE, Map.of("variantId", variantId));
        }
        return item;
    }

    /**
     * A variant inventory knows, active or not: stock that physically moved (a posted goods receipt) is recorded
     * either way. Unknown until its {@code VariantCreated} has been replicated.
     */
    public StockItem knownItem(UUID variantId) {
        return items.find(variantId)
                .orElseThrow(() ->
                        new BusinessRuleViolation(InventoryErrorCode.UNKNOWN_VARIANT, Map.of("variantId", variantId)));
    }

    /** A named batch of the variant ({@code UNKNOWN_BATCH} otherwise). */
    public UUID batch(UUID variantId, UUID batchId) {
        return batches.find(batchId)
                .filter(b -> b.variantId().equals(variantId))
                .map(BatchInfo::id)
                .orElseThrow(() -> new BusinessRuleViolation(
                        InventoryErrorCode.UNKNOWN_BATCH, Map.of("variantId", variantId, "batchId", batchId)));
    }

    /**
     * Rule INV-14: the batch incoming stock of a batch-tracked variant goes into, created when new; a code is generated
     * when none is given, and the expiry is required when the variant tracks it.
     *
     * @return the batch id, or {@code null} when the variant tracks no batches
     */
    public @Nullable UUID incomingBatch(
            StockItem item,
            @Nullable String code,
            @Nullable LocalDate expiresOn,
            Instant receivedAt,
            @Nullable UUID supplierId,
            UUID sourceLineId) {
        if (item.trackExpiry() && expiresOn == null) {
            throw new BusinessRuleViolation(InventoryErrorCode.EXPIRY_REQUIRED, Map.of("variantId", item.variantId()));
        }
        if (!item.trackBatches()) {
            return null;
        }
        String batchCode = code != null && !code.isBlank()
                ? code.strip()
                : "B" + BATCH_DAY.format(receivedAt.atOffset(ZoneOffset.UTC)) + "-"
                        + sourceLineId.toString().substring(24).toUpperCase(java.util.Locale.ROOT);
        return batches.findOrCreate(item.variantId(), batchCode, expiresOn, receivedAt, supplierId)
                .id();
    }
}
