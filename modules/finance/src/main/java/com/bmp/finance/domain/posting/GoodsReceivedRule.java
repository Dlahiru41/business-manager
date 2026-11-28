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

/** {@code GoodsReceived}: Dr INVENTORY · Cr GRNI Σ line totals; a return to the supplier the other way. */
public final class GoodsReceivedRule implements PostingRule<GoodsReceivedRule.Receipt> {

    /** A receipt line at its total ({@code qty × unitCost}), negative on a return. */
    public record Line(UUID lineId, UUID variantId, BigDecimal lineTotal) {}

    /** A posted goods receipt. */
    public record Receipt(
            UUID eventId,
            UUID receiptId,
            String number,
            UUID supplierId,
            UUID locationId,
            LocalDate businessDate,
            List<Line> lines) {

        public Receipt {
            lines = List.copyOf(lines);
        }
    }

    @Override
    public Optional<JournalEntry> post(Receipt r) {
        Dimensions location = Dimensions.location(r.locationId());
        JournalEntry.Builder builder = JournalEntry.builder(
                        SourceType.GOODS_RECEIPT, r.receiptId(), r.eventId(), r.businessDate())
                .memo("Goods receipt " + r.number());
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : r.lines()) {
            builder.debit(SystemKey.INVENTORY, line.lineTotal(), location.withVariant(line.variantId()));
            total = total.add(line.lineTotal());
        }
        builder.credit(SystemKey.GRNI, total, PartyRef.supplier(r.supplierId()), location);
        return builder.buildIfAny();
    }
}
