package com.bmp.finance.adapter.in.event;

import com.bmp.finance.application.command.PaymentPostingHandler;
import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.api.events.PaymentRefunded;
import com.bmp.persistence.event.InboxGuard;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Posts payments, refunds and drawer variances. Inbox-guarded: a redelivered event changes nothing (07 §4). */
@Component
class PaymentsPostingListener {

    static final String CONSUMER = "finance.PaymentsPostingListener";

    private static final Logger log = LoggerFactory.getLogger(PaymentsPostingListener.class);

    private final InboxGuard inbox;
    private final PaymentPostingHandler payments;

    PaymentsPostingListener(InboxGuard inbox, PaymentPostingHandler payments) {
        this.inbox = inbox;
        this.payments = payments;
    }

    @ApplicationModuleListener
    void on(PaymentCaptured event) {
        if (redelivered(event.eventId())) {
            return;
        }
        payments.captured(event, List.of());
    }

    @ApplicationModuleListener
    void on(PaymentRefunded event) {
        if (redelivered(event.eventId())) {
            return;
        }
        payments.refunded(event);
    }

    @ApplicationModuleListener
    void on(DrawerReconciled event) {
        if (redelivered(event.eventId())) {
            return;
        }
        payments.reconciled(event);
    }

    private boolean redelivered(UUID eventId) {
        if (inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        log.debug("Skipping redelivered event eventId={}", eventId);
        return true;
    }
}
