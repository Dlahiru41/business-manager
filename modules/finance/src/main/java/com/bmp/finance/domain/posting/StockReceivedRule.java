package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import com.bmp.shared.error.TechnicalException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code StockReceived} (ADR-032 §5): opening stock Dr INVENTORY · Cr EQUITY; transfers move value between locations
 * only; goods receipts are booked by {@code GoodsReceived}, except what a return to the supplier took at current cost
 * beyond its line total, which goes to PPV; true-ups go to COGS.
 */
public final class StockReceivedRule implements PostingRule<StockReceivedRule.Receipt> {

    /**
     * Stock received.
     *
     * @param kind GOODS_RECEIPT, OPENING_BALANCE or TRANSFER
     * @param receiptLineTotals for a goods receipt: each line's total as procurement posted it, by line id
     */
    public record Receipt(
            UUID eventId,
            String kind,
            UUID sourceId,
            UUID locationId,
            LocalDate businessDate,
            List<StockCost> lines,
            List<StockCost> corrections,
            Map<UUID, BigDecimal> receiptLineTotals) {

        public Receipt {
            lines = List.copyOf(lines);
            corrections = List.copyOf(corrections);
            receiptLineTotals = Map.copyOf(receiptLineTotals);
        }
    }

    @Override
    public Optional<JournalEntry> post(Receipt r) {
        Dimensions location = Dimensions.location(r.locationId());
        JournalEntry.Builder builder = JournalEntry.builder(
                        SourceType.STOCK_RECEIPT, r.sourceId(), r.eventId(), r.businessDate())
                .memo("Stock " + r.kind());
        switch (r.kind()) {
            case "OPENING_BALANCE" -> StockPostings.post(builder, r.lines(), SystemKey.EQUITY, location);
            case "TRANSFER" -> {
                // The value moved between locations; the Inventory account does not change.
            }
            case "GOODS_RECEIPT" -> {
                for (StockCost line : r.lines()) {
                    BigDecimal booked = line.sourceLineId() == null
                            ? null
                            : r.receiptLineTotals().get(line.sourceLineId());
                    if (booked == null) {
                        throw missing(r, "receipt line " + line.sourceLineId());
                    }
                    BigDecimal beyond = line.costAmount().subtract(booked);
                    Dimensions dims = location.withVariant(line.variantId());
                    builder.debit(SystemKey.INVENTORY, beyond, dims);
                    builder.credit(SystemKey.PPV, beyond, dims);
                }
            }
            default -> throw missing(r, "kind " + r.kind());
        }
        StockPostings.corrections(builder, r.corrections(), location);
        return builder.buildIfAny();
    }

    private static TechnicalException missing(Receipt r, String what) {
        return new TechnicalException(
                FinanceErrorCode.POSTING_RULE_MISSING, Map.of("sourceId", r.sourceId(), "missing", what), null);
    }
}
