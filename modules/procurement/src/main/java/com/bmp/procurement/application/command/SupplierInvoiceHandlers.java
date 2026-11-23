package com.bmp.procurement.application.command;

import com.bmp.party.api.dto.PartyInfo;
import com.bmp.procurement.application.ProcurementEvents;
import com.bmp.procurement.application.ProcurementLoader;
import com.bmp.procurement.application.ProcurementReferences;
import com.bmp.procurement.application.ProcurementSettings;
import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.procurement.domain.model.GoodsReceipt;
import com.bmp.procurement.domain.model.InvoiceMatch;
import com.bmp.procurement.domain.model.ReceiptStatus;
import com.bmp.procurement.domain.model.SupplierInvoice;
import com.bmp.procurement.domain.port.GoodsReceiptRepository;
import com.bmp.procurement.domain.port.SupplierInvoiceRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Supplier invoices matched to posted receipts (PRO-05). */
@Service
public class SupplierInvoiceHandlers {

    private static final Logger log = LoggerFactory.getLogger(SupplierInvoiceHandlers.class);

    private final SupplierInvoiceRepository invoices;
    private final GoodsReceiptRepository receipts;
    private final ProcurementLoader loader;
    private final ProcurementReferences references;
    private final ProcurementSettings settings;
    private final ProcurementEvents events;
    private final IdGenerator ids;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters")
    public SupplierInvoiceHandlers(
            SupplierInvoiceRepository invoices,
            GoodsReceiptRepository receipts,
            ProcurementLoader loader,
            ProcurementReferences references,
            ProcurementSettings settings,
            ProcurementEvents events,
            IdGenerator ids,
            Clock clock) {
        this.invoices = invoices;
        this.receipts = receipts;
        this.loader = loader;
        this.references = references;
        this.settings = settings;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    @Observed(name = "bmp.procurement.create-invoice")
    @SuppressWarnings("TooManyParameters")
    public SupplierInvoice create(
            UUID supplierId,
            String supplierRef,
            LocalDate invoiceDate,
            @Nullable LocalDate dueDate,
            BigDecimal subtotal,
            @Nullable BigDecimal tax,
            List<UUID> receiptIds) {
        PartyInfo supplier = references.supplier(supplierId);
        List<InvoiceMatch> matches = new ArrayList<>();
        for (UUID receiptId : new java.util.LinkedHashSet<>(receiptIds)) {
            GoodsReceipt receipt = receipts.findById(receiptId)
                    .orElseThrow(() -> new BusinessRuleViolation(
                            ProcurementErrorCode.UNKNOWN_RECEIPT, Map.of("receiptId", receiptId)));
            requireMatchable(receipt, supplierId);
            receipt.lines();
            matches.add(new InvoiceMatch(receipt.id(), receipt.number(), receipt.total()));
        }
        SupplierInvoice invoice = SupplierInvoice.draft(
                ids.newUuid(),
                TenantContext.require(),
                supplierId,
                supplierRef,
                invoiceDate,
                dueDate != null ? dueDate : invoiceDate.plusDays(supplier.paymentTermsDays()),
                settings.rounding().round(subtotal),
                settings.rounding().round(tax != null ? tax : BigDecimal.ZERO),
                settings.currency(),
                matches);
        SupplierInvoice saved = invoices.saveAndFlush(invoice);
        saved.receipts();
        return saved;
    }

    /** Rule PRO-05: posting marks the receipts invoiced (under their row locks) and publishes the variance. */
    @Transactional
    @Observed(name = "bmp.procurement.post-invoice")
    public SupplierInvoice post(UUID invoiceId) {
        SupplierInvoice invoice = loader.invoice(invoiceId);
        invoice.post(clock.instant());
        for (GoodsReceipt receipt : receipts.lockAll(
                invoice.receipts().stream().map(InvoiceMatch::receiptId).toList())) {
            receipt.markInvoiced();
            receipts.saveAndFlush(receipt);
        }
        SupplierInvoice saved = invoices.saveAndFlush(invoice);
        saved.receipts();
        events.invoicePosted(saved);
        log.atInfo()
                .addKeyValue("invoiceId", saved.id())
                .addKeyValue("priceVariance", saved.priceVariance())
                .log("Supplier invoice posted");
        return saved;
    }

    private static void requireMatchable(GoodsReceipt receipt, UUID supplierId) {
        if (receipt.status() != ReceiptStatus.POSTED) {
            throw new BusinessRuleViolation(ProcurementErrorCode.RECEIPT_NOT_POSTED, Map.of("receiptId", receipt.id()));
        }
        if (!receipt.supplierId().equals(supplierId)) {
            throw new BusinessRuleViolation(ProcurementErrorCode.SUPPLIER_MISMATCH, Map.of("receiptId", receipt.id()));
        }
        if (receipt.invoiced()) {
            throw new BusinessRuleViolation(
                    ProcurementErrorCode.RECEIPT_ALREADY_INVOICED, Map.of("receiptId", receipt.id()));
        }
    }
}
