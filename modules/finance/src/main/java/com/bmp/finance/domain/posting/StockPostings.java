package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.SystemKey;
import java.util.List;

/** Stock value against the account that absorbs it, line by line. */
final class StockPostings {

    private StockPostings() {}

    /** Each cost changes INVENTORY by its sign and {@code against} by the opposite. */
    static void post(JournalEntry.Builder builder, List<StockCost> costs, SystemKey against, Dimensions dims) {
        for (StockCost cost : costs) {
            Dimensions line = dims.withVariant(cost.variantId());
            builder.debit(SystemKey.INVENTORY, cost.costAmount(), line);
            builder.credit(against, cost.costAmount(), line);
        }
    }

    /** ADR-032 §5: true-ups of stock issued below zero correct the cost of goods sold. */
    static void corrections(JournalEntry.Builder builder, List<StockCost> corrections, Dimensions dims) {
        post(builder, corrections, SystemKey.COGS, dims);
    }
}
