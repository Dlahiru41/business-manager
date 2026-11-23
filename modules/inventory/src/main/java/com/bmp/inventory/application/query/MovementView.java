package com.bmp.inventory.application.query;

import com.bmp.inventory.domain.model.StockFlag;
import com.bmp.inventory.domain.service.PlannedMovement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A ledger row (INV-01, INV-02). */
public record MovementView(
        UUID id,
        UUID variantId,
        UUID locationId,
        String stockState,
        @Nullable UUID batchId,
        BigDecimal qtyBase,
        BigDecimal unitCost,
        BigDecimal costAmount,
        String currency,
        String type,
        @Nullable String reasonCode,
        String sourceType,
        @Nullable UUID sourceId,
        @Nullable UUID sourceLineId,
        Instant occurredAt,
        Instant recordedAt,
        @Nullable UUID actorId,
        List<String> flags) {

    public MovementView {
        flags = List.copyOf(flags);
    }

    public static MovementView of(PlannedMovement m, String currency, Instant recordedAt, @Nullable UUID actorId) {
        return new MovementView(
                m.id(),
                m.key().variantId(),
                m.key().locationId(),
                m.key().stockState(),
                m.key().hasBatch() ? m.key().batchId() : null,
                m.qtyBase(),
                m.unitCost(),
                m.costAmount(),
                currency,
                m.type().name(),
                m.reasonCode(),
                m.sourceType(),
                m.sourceId(),
                m.sourceLineId(),
                m.occurredAt(),
                recordedAt,
                actorId,
                m.flags().stream().map(StockFlag::name).sorted().toList());
    }
}
