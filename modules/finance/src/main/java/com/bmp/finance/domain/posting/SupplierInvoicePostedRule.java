package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code SupplierInvoicePosted}: Dr GRNI the matched receipt value · Dr/Cr PPV the difference · Dr TAX_PAYABLE the
 * input tax when reclaimable (else PPV, ADR-032 §6) · Cr AP the total.
 */
public final class SupplierInvoicePostedRule implements PostingRule<SupplierInvoicePostedRule.Invoice> {

    /** A posted supplier invoice. */
    public record Invoice(
            UUID eventId,
            UUID invoiceId,
            String supplierRef,
            UUID supplierId,
            LocalDate invoiceDate,
            BigDecimal tax,
            BigDecimal total,
            BigDecimal receiptValue,
            BigDecimal priceVariance,
            boolean inputTaxReclaimable) {}

    @Override
    public Optional<JournalEntry> post(Invoice i) {
        PartyRef supplier = PartyRef.supplier(i.supplierId());
        return JournalEntry.builder(SourceType.SUPPLIER_INVOICE, i.invoiceId(), i.eventId(), i.invoiceDate())
                .memo("Supplier invoice " + i.supplierRef())
                .debit(SystemKey.GRNI, i.receiptValue(), supplier, Dimensions.NONE)
                .debit(SystemKey.PPV, i.priceVariance(), Dimensions.NONE)
                .debit(i.inputTaxReclaimable() ? SystemKey.TAX_PAYABLE : SystemKey.PPV, i.tax(), Dimensions.NONE)
                .credit(SystemKey.AP, i.total(), supplier, Dimensions.NONE)
                .buildIfAny();
    }
}
