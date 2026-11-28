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
import org.jspecify.annotations.Nullable;

/**
 * {@code PaymentCaptured}: Dr the method's cash-type account · Cr AR (a customer's bill or an account collection) or
 * POS_CLEARING (a walk-in bill).
 */
public final class PaymentCapturedRule implements PostingRule<PaymentCapturedRule.Payment> {

    /** Money taken for a bill or on a customer's account ({@code collection}). */
    public record Payment(
            UUID eventId,
            UUID paymentId,
            boolean collection,
            @Nullable UUID billId,
            @Nullable UUID customerId,
            String method,
            BigDecimal amount,
            LocalDate businessDate) {}

    @Override
    public Optional<JournalEntry> post(Payment p) {
        PartyRef party = p.customerId() == null ? null : PartyRef.customer(p.customerId());
        SystemKey counter = p.collection() || party != null ? SystemKey.AR : SystemKey.POS_CLEARING;
        Dimensions dims = Dimensions.bill(p.billId(), null, null);
        return JournalEntry.builder(
                        p.collection() ? SourceType.COLLECTION : SourceType.BILL_PAYMENT,
                        p.paymentId(),
                        p.eventId(),
                        p.businessDate())
                .memo((p.collection() ? "Collection " : "Bill payment ") + p.method())
                .debit(TenderAccounts.of(p.method()), p.amount(), dims)
                .credit(counter, p.amount(), party, dims)
                .buildIfAny();
    }
}
