package com.bmp.inventory.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A run of a transformation definition (INV-09): what went in at current cost, what came out at its share of that
 * cost, and the yield variance. Runs are never changed.
 *
 * @param yieldVarianceQty Σ actual − Σ expected output, in base units
 */
public record TransformationRun(
        UUID id,
        UUID definitionId,
        int defVersion,
        String kind,
        UUID locationId,
        BigDecimal runs,
        List<RunLine> inputs,
        List<RunLine> outputs,
        BigDecimal yieldVarianceQty,
        BigDecimal totalCost,
        String currency,
        @Nullable String note,
        Instant occurredAt,
        @Nullable UUID actorId,
        boolean negativeStock) {

    public TransformationRun {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }
}
