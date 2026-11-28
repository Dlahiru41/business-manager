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

/** {@code PaymentRefunded}: Dr AR (a customer's) or POS_CLEARING · Cr the method's cash-type account. */
public final class PaymentRefundedRule implements PostingRule<PaymentRefundedRule.Refund> {

    /**
     * Money given back.
     *
     * @param reversesId the payment's journal when a void gives the whole payment back (ADR-032 §4)
     */
    public record Refund(
            UUID eventId,
            UUID refundId,
            @Nullable UUID billId,
            @Nullable UUID customerId,
            String method,
            BigDecimal amount,
            LocalDate businessDate,
            @Nullable UUID reversesId) {}

    @Override
    public Optional<JournalEntry> post(Refund r) {
        PartyRef party = r.customerId() == null ? null : PartyRef.customer(r.customerId());
        SystemKey counter = party != null ? SystemKey.AR : SystemKey.POS_CLEARING;
        Dimensions dims = Dimensions.bill(r.billId(), null, null);
        return JournalEntry.builder(SourceType.PAYMENT_REFUND, r.refundId(), r.eventId(), r.businessDate())
                .memo("Refund " + r.method())
                .reverses(r.reversesId())
                .debit(counter, r.amount(), party, dims)
                .credit(TenderAccounts.of(r.method()), r.amount(), dims)
                .buildIfAny();
    }
}
