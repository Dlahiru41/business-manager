package com.bmp.payments.adapter.in.event;

import com.bmp.payments.application.command.PaymentHandlers;
import com.bmp.payments.application.command.PaymentHandlers.ReturnRefund;
import com.bmp.payments.application.command.PaymentHandlers.Sale;
import com.bmp.payments.application.command.PaymentHandlers.SaleReturn;
import com.bmp.payments.application.command.PaymentHandlers.SaleTender;
import com.bmp.payments.application.command.PaymentHandlers.SaleVoid;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleVoided;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Money for completed, returned and voided bills (PAY-01, PAY-03, T03-07): sales publishes, payments records; the
 * inbox makes redelivery harmless and failures are retried by the event registry.
 */
@Component
class SalePaymentsListener {

    static final String CONSUMER = "payments.SalePaymentsListener";

    private static final Logger log = LoggerFactory.getLogger(SalePaymentsListener.class);

    private final InboxGuard inbox;
    private final PaymentHandlers payments;

    SalePaymentsListener(InboxGuard inbox, PaymentHandlers payments) {
        this.inbox = inbox;
        this.payments = payments;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        if (redelivered(event.eventId())) {
            return;
        }
        payments.captureSale(new Sale(
                event.eventId(),
                event.billId(),
                event.customerId(),
                event.registerId(),
                event.deviceId(),
                event.businessDate(),
                event.completedAt(),
                event.currency(),
                event.tenders().stream()
                        .map(t -> new SaleTender(
                                t.tenderId(), t.method(), t.amount(), t.tendered(), t.change(), t.reference()))
                        .toList()));
    }

    @ApplicationModuleListener
    void on(SaleReturned event) {
        if (redelivered(event.eventId())) {
            return;
        }
        payments.refundReturn(new SaleReturn(
                event.eventId(),
                event.creditNoteId(),
                event.billId(),
                event.registerId(),
                event.reason(),
                event.returnedAt(),
                event.refundTenders().stream()
                        .map(r -> new ReturnRefund(r.refundId(), r.method(), r.amount()))
                        .toList()));
    }

    @ApplicationModuleListener
    void on(SaleVoided event) {
        if (redelivered(event.eventId())) {
            return;
        }
        payments.refundVoid(new SaleVoid(
                event.eventId(),
                event.billId(),
                event.registerId(),
                event.reason(),
                event.voidedAt(),
                event.tenders().stream().anyMatch(t -> !t.method().equals("CREDIT"))));
    }

    private boolean redelivered(UUID eventId) {
        if (inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        log.debug("Skipping redelivered event eventId={}", eventId);
        return true;
    }
}
