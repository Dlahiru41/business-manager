package com.bmp.catalog.application.query;

import com.bmp.catalog.domain.model.TransformationDefinition;
import com.bmp.catalog.domain.model.TransformationLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A transformation definition as the API shows it. */
public record DefinitionView(
        UUID id,
        String code,
        String name,
        String kind,
        @Nullable BigDecimal expectedYieldPct,
        int defVersion,
        String status,
        List<Line> lines,
        long version,
        Instant createdAt,
        @Nullable Instant publishedAt) {

    public DefinitionView {
        lines = List.copyOf(lines);
    }

    public static DefinitionView of(TransformationDefinition definition) {
        return new DefinitionView(
                definition.id(),
                definition.code(),
                definition.name(),
                definition.kind().name(),
                definition.expectedYieldPct(),
                definition.defVersion(),
                definition.status().name(),
                definition.lines().stream().map(DefinitionView::line).toList(),
                definition.version(),
                definition.createdAt(),
                definition.publishedAt());
    }

    private static Line line(TransformationLine line) {
        return new Line(
                line.id(),
                line.direction().name(),
                line.variantId(),
                line.stockState(),
                line.qtyBase(),
                line.packagingUnitId(),
                line.qty(),
                line.costSharePct());
    }

    /** One line. */
    public record Line(
            UUID id,
            String direction,
            UUID variantId,
            String stockState,
            BigDecimal qtyBase,
            @Nullable UUID packagingUnitId,
            @Nullable BigDecimal qty,
            @Nullable BigDecimal costSharePct) {}
}
