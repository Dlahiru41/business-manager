package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code StockTransformed}: no entry when Σ input cost = Σ output cost (the cost is absorbed); a residual goes to
 * WASTAGE and true-ups to COGS (ADR-032 §5).
 */
public final class StockTransformedRule implements PostingRule<StockTransformedRule.Transformation> {

    /** A transformation run: inputs out (negative), outputs in. */
    public record Transformation(
            UUID eventId,
            UUID runId,
            UUID locationId,
            LocalDate businessDate,
            List<StockCost> inputs,
            List<StockCost> outputs,
            List<StockCost> corrections) {

        public Transformation {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            corrections = List.copyOf(corrections);
        }
    }

    @Override
    public Optional<JournalEntry> post(Transformation t) {
        Dimensions location = Dimensions.location(t.locationId());
        BigDecimal residual = Stream.concat(t.inputs().stream(), t.outputs().stream())
                .map(StockCost::costAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        JournalEntry.Builder builder = JournalEntry.builder(
                        SourceType.STOCK_TRANSFORMATION, t.runId(), t.eventId(), t.businessDate())
                .memo("Stock transformation")
                .debit(SystemKey.INVENTORY, residual, location)
                .credit(SystemKey.WASTAGE, residual, location);
        StockPostings.corrections(builder, t.corrections(), location);
        return builder.buildIfAny();
    }
}
