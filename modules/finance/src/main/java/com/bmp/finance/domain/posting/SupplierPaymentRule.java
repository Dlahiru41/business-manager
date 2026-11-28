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

/** Supplier payment (FIN-11): Dr AP · Cr BANK or CASH_DRAWER. */
public final class SupplierPaymentRule implements PostingRule<SupplierPaymentRule.Payment> {

    /** A payment to a supplier. */
    public record Payment(UUID paymentId, UUID supplierId, BigDecimal amount, boolean fromDrawer, LocalDate date) {}

    @Override
    public Optional<JournalEntry> post(Payment p) {
        return JournalEntry.builder(SourceType.SUPPLIER_PAYMENT, p.paymentId(), null, p.date())
                .memo("Supplier payment")
                .debit(SystemKey.AP, p.amount(), PartyRef.supplier(p.supplierId()), Dimensions.NONE)
                .credit(p.fromDrawer() ? SystemKey.CASH_DRAWER : SystemKey.BANK, p.amount(), Dimensions.NONE)
                .buildIfAny();
    }
}
