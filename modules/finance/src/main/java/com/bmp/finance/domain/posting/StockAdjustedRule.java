package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code StockAdjusted}: Dr WASTAGE · Cr INVENTORY the cost for goods spoilt (BREAKAGE, SPOILAGE, SPILLAGE, EXPIRED,
 * DAMAGED), Dr COUNT_VARIANCE for goods missing (THEFT, OTHER) (ADR-032 §5).
 */
public final class StockAdjustedRule implements PostingRule<StockAdjustedRule.Adjustment> {

    static final Set<String> WASTAGE_REASONS = Set.of("BREAKAGE", "SPOILAGE", "SPILLAGE", "EXPIRED", "DAMAGED");

    /** Stock lost, at cost (negative). */
    public record Adjustment(
            UUID eventId,
            UUID adjustmentId,
            String reason,
            UUID locationId,
            LocalDate businessDate,
            List<StockCost> lines) {

        public Adjustment {
            lines = List.copyOf(lines);
        }
    }

    @Override
    public Optional<JournalEntry> post(Adjustment a) {
        SystemKey against = WASTAGE_REASONS.contains(a.reason()) ? SystemKey.WASTAGE : SystemKey.COUNT_VARIANCE;
        JournalEntry.Builder builder = JournalEntry.builder(
                        SourceType.STOCK_ADJUSTMENT, a.adjustmentId(), a.eventId(), a.businessDate())
                .memo("Stock adjustment " + a.reason());
        StockPostings.post(builder, a.lines(), against, Dimensions.location(a.locationId()));
        return builder.buildIfAny();
    }
}
