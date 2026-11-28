package com.bmp.finance.application.command;

import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.application.OpenItems;
import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.port.JournalLedger;
import com.bmp.finance.domain.port.PartyDirectory;
import com.bmp.finance.domain.posting.SaleCompletedRule;
import com.bmp.finance.domain.posting.SaleReturnedRule;
import com.bmp.finance.domain.posting.SaleVoidedRule;
import com.bmp.finance.domain.service.OpenItemSettler.Allocation;
import com.bmp.persistence.event.OutOfOrderEventException;
import com.bmp.sales.api.events.RefundTender;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleTender;
import com.bmp.sales.api.events.SaleVoided;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Journals and receivables of bills: sales, returns and voids (posting-rules.md §2, FIN-07, ADR-032 §3–§4). */
@Service
public class SalesPostingHandler {

    static final String CREDIT = "CREDIT";

    private static final SaleCompletedRule SALE = new SaleCompletedRule();
    private static final SaleReturnedRule RETURN = new SaleReturnedRule();
    private static final SaleVoidedRule VOID = new SaleVoidedRule();

    private final JournalPoster poster;
    private final JournalLedger ledger;
    private final OpenItems openItems;
    private final PartyDirectory parties;
    private final FinanceSettings settings;

    public SalesPostingHandler(
            JournalPoster poster,
            JournalLedger ledger,
            OpenItems openItems,
            PartyDirectory parties,
            FinanceSettings settings) {
        this.poster = poster;
        this.ledger = ledger;
        this.openItems = openItems;
        this.parties = parties;
        this.settings = settings;
    }

    /** Revenue and tax of a bill; its CREDIT tenders open a receivable due after the customer's terms. */
    @Transactional
    public void completed(SaleCompleted e) {
        UUID customer = e.customerId();
        BigDecimal credit = e.tendered(CREDIT);
        LocalDate due = e.businessDate();
        if (customer != null && credit.signum() > 0) {
            int terms = parties.find(customer)
                    .orElseThrow(() -> new OutOfOrderEventException(e.eventId(), "customer " + customer))
                    .paymentTermsDays();
            due = e.businessDate().plusDays(terms);
        }
        poster.post(() -> SALE.post(new SaleCompletedRule.Sale(
                e.eventId(),
                e.billId(),
                e.number(),
                e.businessDate(),
                customer,
                e.locationId().value(),
                e.channel(),
                e.totals().grandTotal(),
                e.lines().stream()
                        .map(l -> new SaleCompletedRule.Line(
                                l.lineId(),
                                l.variantId().value(),
                                l.lineTotal(),
                                l.tax(),
                                l.lineDiscount().add(l.allocatedDiscount())))
                        .toList())));
        if (customer != null) {
            openItems.open(
                    PartyRef.customer(customer),
                    DocumentType.BILL,
                    e.billId(),
                    credit,
                    e.businessDate(),
                    due,
                    List.of());
        }
    }

    /** A credit note: revenue and tax back; CREDIT refunds reduce what the customer owes on the bill first. */
    @Transactional
    public void returned(SaleReturned e) {
        LocalDate date = settings.businessDate(e.returnedAt());
        poster.post(() -> RETURN.post(new SaleReturnedRule.Return(
                e.eventId(),
                e.creditNoteId(),
                e.number(),
                e.billId(),
                e.customerId(),
                e.locationId().value(),
                date,
                e.total(),
                e.lines().stream()
                        .map(l -> new SaleReturnedRule.Line(
                                l.billLineId(), l.variantId().value(), l.amount(), l.tax()))
                        .toList())));
        BigDecimal credited = e.refundTenders().stream()
                .filter(r -> r.method().equals(CREDIT))
                .map(RefundTender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (e.customerId() != null) {
            openItems.open(
                    PartyRef.customer(e.customerId()),
                    DocumentType.RETURN,
                    e.creditNoteId(),
                    credited.negate(),
                    date,
                    date,
                    List.of(new Allocation(e.billId(), credited)));
        }
    }

    /** The exact reversal of the bill's sale; its credit part comes off the customer's account. */
    @Transactional
    public void voided(SaleVoided e) {
        PostedJournal sale = ledger.findFirst(SourceType.SALE, e.billId())
                .orElseThrow(() -> new OutOfOrderEventException(e.eventId(), "the sale journal of bill " + e.billId()));
        LocalDate date = settings.businessDate(e.voidedAt());
        poster.post(() -> VOID.post(new SaleVoidedRule.Voided(e.eventId(), e.billId(), e.number(), date, sale)));
        BigDecimal credit = e.tenders().stream()
                .filter(t -> t.method().equals(CREDIT))
                .map(SaleTender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (e.customerId() != null) {
            openItems.open(
                    PartyRef.customer(e.customerId()),
                    DocumentType.VOID,
                    e.billId(),
                    credit.negate(),
                    date,
                    date,
                    List.of(new Allocation(e.billId(), credit)));
        }
    }
}
