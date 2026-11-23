package com.bmp.inventory.domain.service;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.StockFlag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A movement with its cost, ready for the ledger; a CORRECTION has no quantity, only a cost.
 *
 * @param costAmount signed like {@code qtyBase} (INV-02); any sign for a CORRECTION
 */
public record PlannedMovement(
        UUID id,
        BalanceKey key,
        MovementType type,
        BigDecimal qtyBase,
        BigDecimal unitCost,
        BigDecimal costAmount,
        Set<StockFlag> flags,
        String sourceType,
        @Nullable UUID sourceId,
        @Nullable UUID sourceLineId,
        @Nullable String reasonCode,
        Instant occurredAt) {

    public PlannedMovement {
        flags = Set.copyOf(flags);
    }

    public boolean isCorrection() {
        return type == MovementType.CORRECTION;
    }
}
