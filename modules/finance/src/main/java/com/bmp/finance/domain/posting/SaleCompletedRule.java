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
 * {@code SaleCompleted}: Dr AR (a customer's bill) or POS_CLEARING (walk-in) the grand total · Cr SALES the net before
 * discount · Dr SALES_DISCOUNTS the discount · Cr TAX_PAYABLE the tax, revenue and discount per line (ADR-032 §3).
 */
public final class SaleCompletedRule implements PostingRule<SaleCompletedRule.Sale> {

    /**
     * A bill line as sold.
     *
     * @param lineTotal after discounts, tax included
     * @param discount the line's own and its share of the bill discount
     */
    public record Line(UUID lineId, UUID variantId, BigDecimal lineTotal, BigDecimal tax, BigDecimal discount) {}

    /** A completed bill; {@code grandTotal} is what the customer paid (the device's figure offline). */
    public record Sale(
            UUID eventId,
            UUID billId,
            String number,
            LocalDate businessDate,
            @Nullable UUID customerId,
            UUID locationId,
            String channel,
            BigDecimal grandTotal,
            List<Line> lines) {

        public Sale {
            lines = List.copyOf(lines);
        }
    }

    @Override
    public Optional<JournalEntry> post(Sale sale) {
        PartyRef party = sale.customerId() == null ? null : PartyRef.customer(sale.customerId());
        SystemKey receivable = party == null ? SystemKey.POS_CLEARING : SystemKey.AR;
        Dimensions bill = Dimensions.bill(sale.billId(), sale.locationId(), sale.channel());
        JournalEntry.Builder builder =
                JournalEntry.builder(SourceType.SALE, sale.billId(), sale.eventId(), sale.businessDate());
        builder.debit(receivable, sale.grandTotal(), party, bill);
        BigDecimal lineTotals = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (Line line : sale.lines()) {
            Dimensions dims = bill.withVariant(line.variantId());
            builder.credit(
                    SystemKey.SALES, line.lineTotal().subtract(line.tax()).add(line.discount()), dims);
            builder.debit(SystemKey.SALES_DISCOUNTS, line.discount(), dims);
            lineTotals = lineTotals.add(line.lineTotal());
            tax = tax.add(line.tax());
        }
        builder.credit(SystemKey.TAX_PAYABLE, tax, bill);
        // ADR-032 §3: an offline bill paid at the device's total keeps the difference on revenue or discounts.
        BigDecimal difference = sale.grandTotal().subtract(lineTotals);
        String memo = "Bill " + sale.number();
        if (difference.signum() > 0) {
            builder.credit(SystemKey.SALES, difference, bill);
            memo += "; device totals difference";
        } else if (difference.signum() < 0) {
            builder.debit(SystemKey.SALES_DISCOUNTS, difference.negate(), bill);
            memo += "; device totals difference";
        }
        return builder.memo(memo).buildIfAny();
    }
}
