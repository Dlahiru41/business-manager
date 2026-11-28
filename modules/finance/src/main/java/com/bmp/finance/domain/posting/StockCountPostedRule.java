package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code StockCountPosted}: a loss Dr COUNT_VARIANCE · Cr INVENTORY, a gain the other way. */
public final class StockCountPostedRule implements PostingRule<StockCountPostedRule.Count> {

    /** A posted count's movements at cost. */
    public record Count(UUID eventId, UUID countId, UUID locationId, LocalDate businessDate, List<StockCost> lines) {

        public Count {
            lines = List.copyOf(lines);
        }
    }

    @Override
    public Optional<JournalEntry> post(Count c) {
        JournalEntry.Builder builder = JournalEntry.builder(
                        SourceType.STOCK_COUNT, c.countId(), c.eventId(), c.businessDate())
                .memo("Stock count");
        StockPostings.post(builder, c.lines(), SystemKey.COUNT_VARIANCE, Dimensions.location(c.locationId()));
        return builder.buildIfAny();
    }
}
