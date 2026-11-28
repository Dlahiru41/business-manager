package com.bmp.payments.application;

import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.api.events.PaymentRefunded;
import com.bmp.payments.domain.model.DrawerSession;
import com.bmp.payments.domain.model.Payment;
import com.bmp.payments.domain.model.Refund;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes the payments events inside the transaction that recorded the change (outbox). */
@Component
public class PaymentsEvents {

    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;

    public PaymentsEvents(ApplicationEventPublisher publisher, EventStampSource stamps) {
        this.publisher = publisher;
        this.stamps = stamps;
    }

    /** A payment; {@code allocations} only for account collections. */
    public void captured(Payment p, List<PaymentCaptured.Allocation> allocations) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new PaymentCaptured(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                p.id(),
                0,
                stamp.metadata(),
                p.id(),
                p.kind().name(),
                p.billId(),
                p.tenderId(),
                p.customerId(),
                p.method().name(),
                p.amount(),
                p.tendered(),
                p.changeGiven(),
                p.reference(),
                p.registerId(),
                p.drawerSessionId(),
                p.currency(),
                p.capturedAt(),
                allocations));
    }

    public void refunded(Refund r, Payment of) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new PaymentRefunded(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                of.id(),
                0,
                stamp.metadata(),
                r.id(),
                of.id(),
                of.billId(),
                r.creditNoteId(),
                of.customerId(),
                r.amount(),
                r.method().name(),
                r.reason(),
                r.drawerSessionId(),
                r.currency(),
                r.createdAt()));
    }

    public void reconciled(DrawerSession s) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new DrawerReconciled(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                s.id(),
                s.version(),
                stamp.metadata(),
                s.id(),
                s.registerId(),
                Objects.requireNonNull(s.expectedCash()),
                Objects.requireNonNull(s.countedCash()),
                Objects.requireNonNull(s.variance()),
                s.currency(),
                Objects.requireNonNull(s.reconciledAt())));
    }
}
