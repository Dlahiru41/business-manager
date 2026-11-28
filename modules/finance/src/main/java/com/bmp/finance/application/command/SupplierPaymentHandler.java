package com.bmp.finance.application.command;

import com.bmp.finance.application.Drawers;
import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.application.OpenItems;
import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SupplierPayment;
import com.bmp.finance.domain.port.DocumentLedger;
import com.bmp.finance.domain.port.PartyAccounts;
import com.bmp.finance.domain.port.PartyDirectory;
import com.bmp.finance.domain.posting.SupplierPaymentRule;
import com.bmp.finance.domain.service.OpenItemSettler;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rule FIN-11: supplier payments from the bank or the drawer, settling payables (FIN-07). */
@Service
public class SupplierPaymentHandler {

    private static final Logger log = LoggerFactory.getLogger(SupplierPaymentHandler.class);
    private static final SupplierPaymentRule RULE = new SupplierPaymentRule();

    private final DocumentLedger documents;
    private final JournalPoster poster;
    private final OpenItems openItems;
    private final PartyDirectory parties;
    private final PartyAccounts accounts;
    private final Drawers drawers;
    private final FinanceSettings settings;
    private final Clock clock;

    public SupplierPaymentHandler(
            DocumentLedger documents,
            JournalPoster poster,
            OpenItems openItems,
            PartyDirectory parties,
            PartyAccounts accounts,
            Drawers drawers,
            FinanceSettings settings,
            Clock clock) {
        this.documents = documents;
        this.poster = poster;
        this.openItems = openItems;
        this.parties = parties;
        this.accounts = accounts;
        this.drawers = drawers;
        this.settings = settings;
        this.clock = clock;
    }

    public record PaySupplier(
            UUID id,
            UUID supplierId,
            BigDecimal amount,
            boolean fromDrawer,
            @Nullable UUID drawerSessionId,
            @Nullable String reference,
            @Nullable Instant paidAt,
            List<OpenItemSettler.Allocation> allocations) {

        public PaySupplier {
            allocations = List.copyOf(allocations);
        }
    }

    @Transactional
    public SupplierPayment pay(PaySupplier p) {
        Optional<SupplierPayment> known = documents.supplierPayment(p.id());
        if (known.isPresent()) {
            return known.get();
        }
        PartyRef supplier = PartyRef.supplier(p.supplierId());
        boolean isSupplier = parties.find(p.supplierId())
                .map(t -> t.type().equals("SUPPLIER") || t.type().equals("BOTH"))
                .orElse(false);
        if (!isSupplier && accounts.balance(supplier).isEmpty()) {
            throw new BusinessRuleViolation(FinanceErrorCode.UNKNOWN_SUPPLIER, Map.of("supplierId", p.supplierId()));
        }
        List<OpenItem> open = openItems.openItemsOf(supplier);
        OpenItemSettler.validate(p.amount(), false, open, p.allocations());
        Instant paidAt = p.paidAt() != null ? p.paidAt() : clock.instant();
        UUID session = null;
        if (p.fromDrawer()) {
            session = drawers.move(p.drawerSessionId(), p.id(), "PAID_OUT", p.amount(), "Supplier payment", paidAt)
                    .id();
        }
        LocalDate date = settings.businessDate(paidAt);
        PostedJournal journal = poster.post(() -> RULE.post(
                        new SupplierPaymentRule.Payment(p.id(), p.supplierId(), p.amount(), p.fromDrawer(), date)))
                .orElseThrow();
        SupplierPayment payment = new SupplierPayment(
                p.id(),
                p.supplierId(),
                p.amount(),
                settings.currency(),
                p.fromDrawer(),
                session,
                p.reference(),
                paidAt,
                p.allocations().stream()
                        .map(a -> new SupplierPayment.Allocated(a.documentId(), a.amount()))
                        .toList(),
                journal.id());
        documents.append(payment);
        openItems.open(
                supplier, DocumentType.SUPPLIER_PAYMENT, p.id(), p.amount().negate(), date, date, p.allocations());
        log.atInfo()
                .addKeyValue("supplierPaymentId", payment.id())
                .addKeyValue("fromDrawer", payment.fromDrawer())
                .log("Supplier paid");
        return payment;
    }
}
