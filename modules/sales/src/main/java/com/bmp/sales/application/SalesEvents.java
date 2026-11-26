package com.bmp.sales.application;

import com.bmp.sales.api.events.RefundTender;
import com.bmp.sales.api.events.ReturnedLine;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleLine;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleTender;
import com.bmp.sales.api.events.SaleTotals;
import com.bmp.sales.api.events.SaleVoided;
import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillTender;
import com.bmp.sales.domain.model.CreditNote;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes {@code SaleCompleted}, {@code SaleVoided} and {@code SaleReturned} from the saved documents. */
@Component
public class SalesEvents {

    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;

    public SalesEvents(ApplicationEventPublisher publisher, EventStampSource stamps) {
        this.publisher = publisher;
        this.stamps = stamps;
    }

    public void completed(Bill bill) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new SaleCompleted(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                bill.id(),
                bill.version(),
                stamp.metadata(),
                bill.id(),
                bill.number(),
                bill.channel().name(),
                bill.customerId(),
                LocationId.of(bill.locationId()),
                bill.registerId(),
                bill.shiftId(),
                bill.deviceId(),
                bill.cashierId(),
                bill.priceListId(),
                bill.priceListVersion(),
                Objects.requireNonNull(bill.businessDate()),
                Objects.requireNonNull(bill.completedAt()),
                bill.currency(),
                bill.offline(),
                bill.flags().stream().map(Enum::name).sorted().toList(),
                bill.lines().stream()
                        .map(l -> new SaleLine(
                                l.id(),
                                VariantId.of(l.variantId()),
                                PackagingUnitId.of(l.packagingUnitId()),
                                l.description(),
                                l.qty(),
                                l.qtyBase(),
                                l.stockState(),
                                l.weighed(),
                                l.unitPrice(),
                                l.lineDiscount(),
                                l.allocatedBillDiscount(),
                                l.taxAmount(),
                                l.lineTotal()))
                        .toList(),
                new SaleTotals(
                        bill.payableTotals().subtotal(),
                        bill.payableTotals().discountTotal(),
                        bill.payableTotals().taxTotal(),
                        bill.payableTotals().grandTotal()),
                tenders(bill)));
    }

    public void voided(Bill bill) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new SaleVoided(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                bill.id(),
                bill.version(),
                stamp.metadata(),
                bill.id(),
                bill.number(),
                bill.customerId(),
                LocationId.of(bill.locationId()),
                bill.registerId(),
                bill.currency(),
                Objects.requireNonNull(bill.voidReason()),
                bill.voidApprovedBy(),
                bill.payableTotals().grandTotal(),
                tenders(bill),
                Objects.requireNonNull(bill.voidedAt())));
    }

    public void returned(Bill bill, CreditNote note) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new SaleReturned(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                bill.id(),
                bill.version(),
                stamp.metadata(),
                note.id(),
                note.number(),
                bill.id(),
                bill.number(),
                bill.customerId(),
                LocationId.of(bill.locationId()),
                bill.registerId(),
                bill.currency(),
                note.reason(),
                note.total(),
                note.taxTotal(),
                note.lines().stream()
                        .map(l -> new ReturnedLine(
                                l.billLineId(),
                                VariantId.of(l.variantId()),
                                l.qty(),
                                l.qtyBase(),
                                l.stockState(),
                                l.amount(),
                                l.taxAmount(),
                                l.restock()))
                        .toList(),
                note.refundTenders().stream()
                        .map(r -> new RefundTender(
                                r.refundId(), r.method().name(), r.amount(), r.reference(), r.originalTenderId()))
                        .toList(),
                note.returnedAt()));
    }

    private static List<SaleTender> tenders(Bill bill) {
        return bill.tenders().stream().map(SalesEvents::tender).toList();
    }

    private static SaleTender tender(BillTender t) {
        return new SaleTender(t.id(), t.method().name(), t.amount(), t.tendered(), t.changeGiven(), t.reference());
    }
}
