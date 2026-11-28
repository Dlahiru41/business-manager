package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code StockIssued}: Dr COGS · Cr INVENTORY Σ cost for a sale; returns and voids bring the cost back the other way;
 * true-ups go to COGS (ADR-032 §5).
 */
public final class StockIssuedRule implements PostingRule<StockIssuedRule.Issue> {

    /**
     * Stock issued for a bill, or back from its return or void.
     *
     * @param kind SALE, SALE_RETURN or SALE_VOID
     * @param reversesId the sale's stock journal for a void
     */
    public record Issue(
            UUID eventId,
            String kind,
            UUID sourceId,
            UUID billId,
            UUID locationId,
            LocalDate businessDate,
            List<StockCost> lines,
            List<StockCost> corrections,
            @Nullable UUID reversesId) {

        public Issue {
            lines = List.copyOf(lines);
            corrections = List.copyOf(corrections);
        }
    }

    @Override
    public Optional<JournalEntry> post(Issue issue) {
        Dimensions dims = Dimensions.bill(issue.billId(), issue.locationId(), null);
        JournalEntry.Builder builder = JournalEntry.builder(
                        SourceType.STOCK_ISSUE, issue.sourceId(), issue.eventId(), issue.businessDate())
                .memo("Stock " + issue.kind())
                .reverses(issue.reversesId());
        StockPostings.post(builder, issue.lines(), SystemKey.COGS, dims);
        StockPostings.corrections(builder, issue.corrections(), dims);
        return builder.buildIfAny();
    }
}
