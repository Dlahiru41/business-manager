package com.bmp.catalog.domain.service;

import com.bmp.catalog.domain.model.Direction;
import com.bmp.catalog.domain.model.LineDraft;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.StockStates;
import com.bmp.catalog.domain.model.TransformationKind;
import com.bmp.catalog.domain.model.Variant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code LOOSE_REPACK} (loose soya, dried fish, spices): base unit g (or ml), weighed sale, bought in bulk units; the
 * shop's own packets (sell-only units) are kept in their own stock state {@code PACKED_<CODE>} so packets are counted
 * apart from loose stock. Rule CAT-09: every own packet gets a {@code REPACK} definition, its base quantity loose → 1
 * packet.
 */
public record LooseRepackTemplate(TemplateDefaults defaults) implements TemplateStrategy {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override
    public ProductTemplate template() {
        return ProductTemplate.LOOSE_REPACK;
    }

    @Override
    public List<String> stockStates(List<UnitSpec> units) {
        Set<String> states = new LinkedHashSet<>(defaults.stockStates());
        units.stream().filter(LooseRepackTemplate::isOwnPacket).forEach(u -> states.add(defaultStockState(u)));
        return new ArrayList<>(states);
    }

    @Override
    public String defaultStockState(UnitSpec unit) {
        return isOwnPacket(unit) ? StockStates.packedStateFor(unit.code()) : StockStates.DEFAULT;
    }

    @Override
    public List<DefinitionPlan> transformations(String productCode, Variant variant) {
        return variant.packagingUnits().stream()
                .filter(unit -> !unit.isBase() && unit.isActive())
                .filter(unit -> unit.stockState().startsWith(StockStates.PACKED_PREFIX))
                .map(unit -> repack(productCode, variant, unit))
                .toList();
    }

    /** A unit the shop packs itself: sold, not bought. */
    private static boolean isOwnPacket(UnitSpec unit) {
        return !unit.isBase() && unit.canSell() && !unit.canBuy();
    }

    private static DefinitionPlan repack(String productCode, Variant variant, PackagingUnit unit) {
        LineDraft in = new LineDraft(
                Direction.IN,
                variant.id().value(),
                StockStates.DEFAULT,
                unit.qtyInBase(),
                unit.id(),
                BigDecimal.ONE,
                null);
        LineDraft out = new LineDraft(
                Direction.OUT,
                variant.id().value(),
                unit.stockState(),
                unit.qtyInBase(),
                unit.id(),
                BigDecimal.ONE,
                HUNDRED);
        return new DefinitionPlan(
                DefinitionCodes.of(productCode, "REPACK", unit.code()),
                "Pack " + unit.name(),
                TransformationKind.REPACK,
                HUNDRED,
                List.of(in, out));
    }
}
