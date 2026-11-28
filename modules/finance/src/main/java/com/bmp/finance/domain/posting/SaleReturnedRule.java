package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code SaleReturned}: Dr SALES_RETURNS the net · Dr TAX_PAYABLE the tax · Cr AR or POS_CLEARING the total; the cost
 * of restocked lines comes back through {@code StockIssued} (ADR-032 §4).
 */
public final class SaleReturnedRule implements PostingRule<SaleReturnedRule.Return> {

    /**
     * A returned bill line.
     *
     * @param amount what it is credited, tax included
     */
    public record Line(UUID billLineId, UUID variantId, BigDecimal amount, BigDecimal tax) {}

    /** A credit note. */
    public record Return(
            UUID eventId,
            UUID creditNoteId,
            String number,
            UUID billId,
            @Nullable UUID customerId,
            UUID locationId,
            LocalDate businessDate,
            BigDecimal total,
            List<Line> lines) {

        public Return {
            lines = List.copyOf(lines);
        }
    }

    @Override
    public Optional<JournalEntry> post(Return r) {
        PartyRef party = r.customerId() == null ? null : PartyRef.customer(r.customerId());
        SystemKey receivable = party == null ? SystemKey.POS_CLEARING : SystemKey.AR;
        Dimensions bill = Dimensions.bill(r.billId(), r.locationId(), null);
        JournalEntry.Builder builder = JournalEntry.builder(
                        SourceType.SALE_RETURN, r.creditNoteId(), r.eventId(), r.businessDate())
                .memo("Credit note " + r.number());
        BigDecimal tax = BigDecimal.ZERO;
        for (Line line : r.lines()) {
            builder.debit(
                    SystemKey.SALES_RETURNS, line.amount().subtract(line.tax()), bill.withVariant(line.variantId()));
            tax = tax.add(line.tax());
        }
        builder.debit(SystemKey.TAX_PAYABLE, tax, bill);
        builder.credit(receivable, r.total(), party, bill);
        return builder.buildIfAny();
    }
}
