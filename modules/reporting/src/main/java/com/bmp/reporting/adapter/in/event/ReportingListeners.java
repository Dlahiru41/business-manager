package com.bmp.reporting.adapter.in.event;

import com.bmp.catalog.api.events.ProductCreated;
import com.bmp.catalog.api.events.ProductUpdated;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.catalog.api.events.VariantUpdated;
import com.bmp.finance.api.events.ExpenseRecorded;
import com.bmp.inventory.api.events.StockAdjusted;
import com.bmp.inventory.api.events.StockCountPosted;
import com.bmp.inventory.api.events.StockIssued;
import com.bmp.inventory.api.events.StockMovementLine;
import com.bmp.inventory.api.events.StockTransformed;
import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.api.events.PaymentRefunded;
import com.bmp.reporting.application.Rebuild;
import com.bmp.reporting.application.RebuildRequested;
import com.bmp.reporting.application.Recorder;
import com.bmp.reporting.application.ReportingSettings;
import com.bmp.reporting.domain.model.Fact;
import com.bmp.sales.api.events.RefundTender;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleVoided;
import com.bmp.shared.event.DomainEvent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Rule REP-01: the events the read models are built from (T04-05), each mapped to reporting's own fact and recorded;
 * the event log makes a redelivered event harmless (ADR-033 §1).
 */
@Component
class ReportingListeners {

    private final Recorder recorder;
    private final ReportingSettings settings;
    private final Rebuild rebuild;

    ReportingListeners(Recorder recorder, ReportingSettings settings, Rebuild rebuild) {
        this.recorder = recorder;
        this.settings = settings;
        this.rebuild = rebuild;
    }

    private void record(DomainEvent event, Fact fact) {
        recorder.record(event.eventId(), event.occurredAt(), fact);
    }

    @ApplicationModuleListener
    void on(SaleCompleted e) {
        record(
                e,
                new Fact.Sale(
                        e.billId(),
                        e.number(),
                        e.businessDate(),
                        e.locationId().value(),
                        e.registerId(),
                        e.channel(),
                        e.customerId(),
                        e.cashierId(),
                        settings.hour(e.completedAt()),
                        e.completedAt(),
                        e.totals().grandTotal(),
                        e.totals().taxTotal(),
                        e.totals().discountTotal(),
                        e.lines().stream()
                                .map(l -> new Fact.SaleLine(
                                        l.lineId(),
                                        l.variantId().value(),
                                        l.packagingUnitId().value(),
                                        l.qty(),
                                        l.qtyBase(),
                                        l.lineTotal().subtract(l.tax()),
                                        l.lineDiscount().add(l.allocatedDiscount()),
                                        l.tax()))
                                .toList()));
    }

    @ApplicationModuleListener
    void on(SaleReturned e) {
        record(
                e,
                new Fact.Return(
                        e.creditNoteId(),
                        e.billId(),
                        settings.businessDate(e.returnedAt()),
                        e.locationId().value(),
                        e.returnedAt(),
                        e.refundTenders().stream()
                                .filter(r -> !r.method().equals("CREDIT"))
                                .map(RefundTender::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.lines().stream()
                                .map(l -> new Fact.ReturnLine(
                                        l.billLineId(),
                                        l.variantId().value(),
                                        l.qty(),
                                        l.qtyBase(),
                                        l.amount().subtract(l.tax()),
                                        l.tax()))
                                .toList()));
    }

    @ApplicationModuleListener
    void on(SaleVoided e) {
        record(e, new Fact.Voided(e.billId(), settings.businessDate(e.voidedAt())));
    }

    /** The cost of each bill line, summed over its movements (true-ups are finance's COGS, not a line's). */
    @ApplicationModuleListener
    void on(StockIssued e) {
        Map<UUID, BigDecimal> costs = new LinkedHashMap<>();
        for (StockMovementLine l : e.lines()) {
            if (l.sourceLineId() != null) {
                costs.merge(l.sourceLineId(), l.costAmount().negate(), BigDecimal::add);
            }
        }
        record(
                e,
                new Fact.Cost(
                        e.sourceType(),
                        e.sourceId(),
                        e.billId(),
                        costs.entrySet().stream()
                                .map(c -> new Fact.LineCost(c.getKey(), c.getValue()))
                                .toList()));
    }

    @ApplicationModuleListener
    void on(StockAdjusted e) {
        record(
                e,
                new Fact.Movement(
                        "ADJUSTMENT",
                        settings.businessDate(e.occurredAt()),
                        e.locationId().value(),
                        e.reason(),
                        movements(e.lines().stream())));
    }

    @ApplicationModuleListener
    void on(StockCountPosted e) {
        record(
                e,
                new Fact.Movement(
                        "COUNT",
                        settings.businessDate(e.occurredAt()),
                        e.locationId().value(),
                        null,
                        movements(e.lines().stream())));
    }

    @ApplicationModuleListener
    void on(StockTransformed e) {
        record(
                e,
                new Fact.Movement(
                        "TRANSFORMATION",
                        settings.businessDate(e.occurredAt()),
                        e.locationId().value(),
                        null,
                        movements(Stream.concat(e.inputs().stream(), e.outputs().stream()))));
    }

    @ApplicationModuleListener
    void on(PaymentCaptured e) {
        record(
                e,
                new Fact.Payment(
                        e.paymentId(),
                        "CAPTURE",
                        settings.businessDate(e.capturedAt()),
                        e.method(),
                        e.amount(),
                        e.registerId(),
                        e.billId(),
                        null));
    }

    @ApplicationModuleListener
    void on(PaymentRefunded e) {
        record(
                e,
                new Fact.Payment(
                        e.refundId(),
                        "REFUND",
                        settings.businessDate(e.refundedAt()),
                        e.method(),
                        e.amount().negate(),
                        null,
                        e.billId(),
                        e.creditNoteId()));
    }

    @ApplicationModuleListener
    void on(DrawerReconciled e) {
        record(
                e,
                new Fact.Drawer(
                        e.sessionId(),
                        e.registerId(),
                        settings.businessDate(e.reconciledAt()),
                        e.expected(),
                        e.counted(),
                        e.variance(),
                        e.reconciledAt()));
    }

    @ApplicationModuleListener
    void on(ExpenseRecorded e) {
        record(e, new Fact.Expense(e.expenseId(), e.expenseDate(), e.categoryId(), e.amount()));
    }

    @ApplicationModuleListener
    void on(VariantCreated e) {
        record(
                e,
                new Fact.VariantNamed(
                        e.variantId().value(), e.productId().value(), e.sku(), e.name(), e.aggregateVersion()));
    }

    @ApplicationModuleListener
    void on(VariantUpdated e) {
        record(
                e,
                new Fact.VariantNamed(
                        e.variantId().value(), e.productId().value(), e.sku(), e.name(), e.aggregateVersion()));
    }

    @ApplicationModuleListener
    void on(ProductCreated e) {
        record(e, new Fact.ProductNamed(e.productId().value(), e.name(), e.categoryId(), e.aggregateVersion()));
    }

    @ApplicationModuleListener
    void on(ProductUpdated e) {
        record(e, new Fact.ProductNamed(e.productId().value(), e.name(), e.categoryId(), e.aggregateVersion()));
    }

    @ApplicationModuleListener
    void on(RebuildRequested e) {
        rebuild.run(e.operationId());
    }

    private static List<Fact.MovementLine> movements(Stream<StockMovementLine> lines) {
        return lines.map(
                        l -> new Fact.MovementLine(l.movementId(), l.variantId().value(), l.qtyBase(), l.costAmount()))
                .toList();
    }
}
