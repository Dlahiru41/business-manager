package com.bmp.catalog.domain.service;

import com.bmp.catalog.domain.model.Direction;
import com.bmp.catalog.domain.model.LineDraft;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.StockStates;
import com.bmp.catalog.domain.model.TransformationKind;
import com.bmp.catalog.domain.model.Variant;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code BULK_BREAK} (cooking oil, rice sacks, fabric rolls): one variant with states {@code SEALED}/{@code OPEN};
 * bought units are kept sealed, smaller units are sold from the open stock (sell mode {@code BREAK_ON_SALE}). Rule
 * CAT-09: every sealed bulk unit gets an {@code OPEN} definition, 1 bulk unit SEALED → its base quantity OPEN.
 */
public record BulkBreakTemplate(TemplateDefaults defaults) implements TemplateStrategy {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override
    public ProductTemplate template() {
        return ProductTemplate.BULK_BREAK;
    }

    @Override
    public List<String> stockStates(List<UnitSpec> units) {
        return defaults.stockStates();
    }

    /** Buyable units are the sealed bulk; everything else, the base unit included, is open stock. */
    @Override
    public String defaultStockState(UnitSpec unit) {
        return !unit.isBase() && unit.canBuy() ? StockStates.SEALED : StockStates.OPEN;
    }

    @Override
    public List<DefinitionPlan> transformations(String productCode, Variant variant) {
        return variant.packagingUnits().stream()
                .filter(unit -> !unit.isBase() && unit.isActive())
                .filter(unit -> unit.stockState().equals(StockStates.SEALED))
                .map(unit -> open(productCode, variant, unit))
                .toList();
    }

    private static DefinitionPlan open(String productCode, Variant variant, PackagingUnit unit) {
        // Both lines are sized by the bulk unit, so a changed conversion (CAT-14) resizes them together.
        LineDraft in = new LineDraft(
                Direction.IN,
                variant.id().value(),
                StockStates.SEALED,
                unit.qtyInBase(),
                unit.id(),
                BigDecimal.ONE,
                null);
        LineDraft out = new LineDraft(
                Direction.OUT,
                variant.id().value(),
                StockStates.OPEN,
                unit.qtyInBase(),
                unit.id(),
                BigDecimal.ONE,
                HUNDRED);
        return new DefinitionPlan(
                DefinitionCodes.of(productCode, "OPEN", unit.code()),
                "Open " + unit.name(),
                TransformationKind.OPEN,
                HUNDRED,
                List.of(in, out));
    }
}
