package com.bmp.finance.application.command;

import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.application.OpenItems;
import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.port.JournalLedger;
import com.bmp.finance.domain.posting.DrawerReconciledRule;
import com.bmp.finance.domain.posting.PaymentCapturedRule;
import com.bmp.finance.domain.posting.PaymentRefundedRule;
import com.bmp.finance.domain.service.OpenItemSettler.Allocation;
import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.api.events.PaymentRefunded;
import com.bmp.persistence.event.OutOfOrderEventException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Journals and receivables of money taken and given back, and of drawer variances (posting-rules.md §2). */
@Service
public class PaymentPostingHandler {

    static final String ACCOUNT = "ACCOUNT";

    private static final PaymentCapturedRule CAPTURED = new PaymentCapturedRule();
    private static final PaymentRefundedRule REFUNDED = new PaymentRefundedRule();
    private static final DrawerReconciledRule RECONCILED = new DrawerReconciledRule();

    private final JournalPoster poster;
    private final JournalLedger ledger;
    private final OpenItems openItems;
    private final FinanceSettings settings;

    public PaymentPostingHandler(
            JournalPoster poster, JournalLedger ledger, OpenItems openItems, FinanceSettings settings) {
        this.poster = poster;
        this.ledger = ledger;
        this.openItems = openItems;
        this.settings = settings;
    }

    /** A payment of a bill, or a collection on account that settles the customer's bills (FIN-07). */
    @Transactional
    public void captured(PaymentCaptured e, List<Allocation> allocations) {
        boolean collection = e.kind().equals(ACCOUNT);
        LocalDate date = settings.businessDate(e.capturedAt());
        poster.post(() -> CAPTURED.post(new PaymentCapturedRule.Payment(
                e.eventId(), e.paymentId(), collection, e.billId(), e.customerId(), e.method(), e.amount(), date)));
        if (collection && e.customerId() != null) {
            openItems.open(
                    PartyRef.customer(e.customerId()),
                    DocumentType.COLLECTION,
                    e.paymentId(),
                    e.amount().negate(),
                    date,
                    date,
                    allocations);
        }
    }

    /**
     * Money back. A void gives a whole payment back and points at its journal (ADR-032 §4); a refund of a collection
     * is owed again.
     */
    @Transactional
    public void refunded(PaymentRefunded e) {
        UUID reverses = null;
        if (e.creditNoteId() == null && e.billId() != null) {
            reverses = ledger.findFirst(SourceType.BILL_PAYMENT, e.paymentId())
                    .map(PostedJournal::id)
                    .orElseThrow(
                            () -> new OutOfOrderEventException(e.eventId(), "the journal of payment " + e.paymentId()));
        }
        LocalDate date = settings.businessDate(e.refundedAt());
        UUID reversesId = reverses;
        poster.post(() -> REFUNDED.post(new PaymentRefundedRule.Refund(
                e.eventId(), e.refundId(), e.billId(), e.customerId(), e.method(), e.amount(), date, reversesId)));
        if (e.billId() == null && e.customerId() != null) {
            openItems.open(
                    PartyRef.customer(e.customerId()),
                    DocumentType.REFUND,
                    e.refundId(),
                    e.amount(),
                    date,
                    date,
                    List.of());
        }
    }

    /** The drawer's variance to cash over or short. */
    @Transactional
    public void reconciled(DrawerReconciled e) {
        LocalDate date = settings.businessDate(e.reconciledAt());
        poster.post(() -> RECONCILED.post(
                new DrawerReconciledRule.Reconciliation(e.eventId(), e.sessionId(), e.variance(), date)));
    }
}
