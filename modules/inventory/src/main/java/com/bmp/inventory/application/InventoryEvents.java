package com.bmp.inventory.application;

import com.bmp.inventory.api.events.StockAdjusted;
import com.bmp.inventory.api.events.StockCountPosted;
import com.bmp.inventory.api.events.StockMovementLine;
import com.bmp.inventory.api.events.StockReceived;
import com.bmp.inventory.api.events.StockTransformed;
import com.bmp.inventory.domain.model.StockCount;
import com.bmp.inventory.domain.model.TransformationRun;
import com.bmp.inventory.domain.model.WastageReason;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes the inventory events of finished postings (07 §3); every event carries its movements and costs. */
@Component
public class InventoryEvents {

    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;

    public InventoryEvents(ApplicationEventPublisher publisher, EventStampSource stamps) {
        this.publisher = publisher;
        this.stamps = stamps;
    }

    public void received(
            String sourceType, UUID sourceId, UUID locationId, String currency, List<PlannedMovement> posted) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new StockReceived(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                sourceId,
                0,
                stamp.metadata(),
                sourceType,
                sourceId,
                LocationId.of(locationId),
                currency,
                lines(posted.stream().filter(m -> !m.isCorrection()).toList()),
                lines(posted.stream().filter(PlannedMovement::isCorrection).toList())));
    }

    public void adjusted(
            UUID adjustmentId,
            WastageReason reason,
            @Nullable String note,
            UUID locationId,
            String currency,
            List<PlannedMovement> posted) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new StockAdjusted(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                adjustmentId,
                0,
                stamp.metadata(),
                adjustmentId,
                reason.name(),
                note,
                LocationId.of(locationId),
                currency,
                lines(posted)));
    }

    public void transformed(TransformationRun run, List<PlannedMovement> inputs, List<PlannedMovement> outputs) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new StockTransformed(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                run.id(),
                0,
                stamp.metadata(),
                run.id(),
                run.definitionId(),
                run.defVersion(),
                run.kind(),
                LocationId.of(run.locationId()),
                run.currency(),
                lines(inputs),
                lines(outputs),
                run.yieldVarianceQty(),
                run.totalCost()));
    }

    public void countPosted(StockCount count, String currency, List<PlannedMovement> posted) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new StockCountPosted(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                count.id(),
                count.version(),
                stamp.metadata(),
                count.id(),
                LocationId.of(count.locationId()),
                currency,
                lines(posted),
                posted.stream().map(PlannedMovement::costAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    private static List<StockMovementLine> lines(List<PlannedMovement> movements) {
        return movements.stream()
                .map(m -> new StockMovementLine(
                        m.id(),
                        m.sourceLineId(),
                        VariantId.of(m.key().variantId()),
                        m.key().stockState(),
                        m.key().hasBatch() ? m.key().batchId() : null,
                        m.qtyBase(),
                        m.unitCost(),
                        m.costAmount()))
                .toList();
    }
}
