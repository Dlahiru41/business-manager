package com.bmp.catalog.domain.service;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.ProductConversion;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Converts quantities between units for one product (rule CAT-03): units of the same dimension convert through their
 * SI factors (g → kg, ml → l); units of different dimensions only through one of the product's conversions (oil: kg →
 * ml), possibly with SI steps on either side (g → kg → ml). Anything else is {@code UNIT_NOT_CONVERTIBLE}. Results
 * keep full precision ({@link MathContext#DECIMAL128}); callers round to storage scale.
 */
public final class UnitConverter {

    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private final Map<UUID, UnitOfMeasure> units = new HashMap<>();
    private final List<ProductConversion> conversions;

    /**
     * @param units every unit that may appear (standard and the tenant's)
     * @param conversions the product's conversions
     */
    public UnitConverter(Collection<UnitOfMeasure> units, List<ProductConversion> conversions) {
        units.forEach(unit -> this.units.put(unit.id(), unit));
        this.conversions = List.copyOf(conversions);
    }

    /** {@code qty} of unit {@code from} expressed in unit {@code to}. */
    public BigDecimal convert(BigDecimal qty, UUID from, UUID to) {
        return qty.multiply(factor(from, to), PRECISION);
    }

    /** How many {@code to} units one {@code from} unit is. */
    public BigDecimal factor(UUID from, UUID to) {
        UnitOfMeasure source = unit(from);
        UnitOfMeasure target = unit(to);
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }
        return siFactor(source, target)
                .or(() -> throughConversion(source, target))
                .orElseThrow(() -> new BusinessRuleViolation(
                        CatalogErrorCode.UNIT_NOT_CONVERTIBLE,
                        "No conversion from " + source.code() + " to " + target.code(),
                        Map.of("fromUnit", source.code(), "toUnit", target.code())));
    }

    /** Whether the two units convert for this product. */
    public boolean canConvert(UUID from, UUID to) {
        try {
            factor(from, to);
            return true;
        } catch (BusinessRuleViolation e) {
            return false;
        }
    }

    private Optional<BigDecimal> throughConversion(UnitOfMeasure source, UnitOfMeasure target) {
        for (ProductConversion conversion : conversions) {
            UnitOfMeasure x = unit(conversion.fromUnitId());
            UnitOfMeasure y = unit(conversion.toUnitId());
            Optional<BigDecimal> forward = siFactor(source, x)
                    .flatMap(toX -> siFactor(y, target)
                            .map(fromY ->
                                    toX.multiply(conversion.factor(), PRECISION).multiply(fromY, PRECISION)));
            if (forward.isPresent()) {
                return forward;
            }
            BigDecimal inverse = BigDecimal.ONE.divide(conversion.factor(), PRECISION);
            Optional<BigDecimal> backward = siFactor(source, y)
                    .flatMap(toY -> siFactor(x, target)
                            .map(fromX -> toY.multiply(inverse, PRECISION).multiply(fromX, PRECISION)));
            if (backward.isPresent()) {
                return backward;
            }
        }
        return Optional.empty();
    }

    /** The factor between two units of one dimension by SI factors (or 1 for the same unit). */
    private static Optional<BigDecimal> siFactor(UnitOfMeasure source, UnitOfMeasure target) {
        if (source.id().equals(target.id())) {
            return Optional.of(BigDecimal.ONE);
        }
        if (!source.convertsBySiFactorTo(target)) {
            return Optional.empty();
        }
        BigDecimal sourceSi = Objects.requireNonNull(source.toSiFactor());
        BigDecimal targetSi = Objects.requireNonNull(target.toSiFactor());
        return Optional.of(sourceSi.divide(targetSi, PRECISION));
    }

    private UnitOfMeasure unit(UUID id) {
        UnitOfMeasure unit = units.get(id);
        if (unit == null) {
            throw new BusinessRuleViolation(CatalogErrorCode.UNKNOWN_UNIT, Map.of("unitId", id));
        }
        return unit;
    }
}
