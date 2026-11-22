package com.bmp.catalog.application.command;

import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.DefinedQuantity;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.PackagingUnitDraft;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.service.TemplateStrategy;
import com.bmp.catalog.domain.service.TemplateStrategy.UnitSpec;
import com.bmp.catalog.domain.service.UnitConverter;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Turns requested packaging units into drafts for a product: sizes resolved through the unit converter (CAT-03,
 * CAT-04), flags and stock states defaulted by the template (CAT-09). The base unit always comes first (CAT-02); a
 * requested unit with the base unit's code configures it.
 */
final class PackagingDrafts {

    private final TemplateStrategy strategy;
    private final UnitOfMeasure baseUnit;
    private final UnitDirectory directory;
    private final UnitConverter converter;

    PackagingDrafts(
            TemplateStrategy strategy, UnitOfMeasure baseUnit, UnitDirectory directory, UnitConverter converter) {
        this.strategy = strategy;
        this.baseUnit = baseUnit;
        this.directory = directory;
        this.converter = converter;
    }

    /** What the template needs to decide stock states: the base unit and every requested unit with its flags. */
    List<UnitSpec> unitSpecs(List<PackagingUnitSpec> specs) {
        List<UnitSpec> result = new ArrayList<>();
        Optional<PackagingUnitSpec> baseSpec = baseSpec(specs);
        result.add(new UnitSpec(
                baseUnit.code(),
                true,
                flag(
                        baseSpec.map(PackagingUnitSpec::canBuy).orElse(null),
                        strategy.defaults().baseCanBuy()),
                flag(
                        baseSpec.map(PackagingUnitSpec::canSell).orElse(null),
                        strategy.defaults().baseCanSell())));
        for (PackagingUnitSpec spec : specs) {
            if (!isBase(spec)) {
                result.add(new UnitSpec(spec.code(), false, flag(spec.canBuy(), false), flag(spec.canSell(), false)));
            }
        }
        return result;
    }

    /** The base unit followed by every other requested unit. */
    List<PackagingUnitDraft> all(List<PackagingUnitSpec> specs) {
        List<PackagingUnitDraft> drafts = new ArrayList<>();
        drafts.add(base(baseSpec(specs).orElse(null)));
        for (PackagingUnitSpec spec : specs) {
            if (!isBase(spec)) {
                drafts.add(other(spec));
            }
        }
        return drafts;
    }

    /** The base unit, configured by the request when it names it (its size must then be 1). */
    PackagingUnitDraft base(@Nullable PackagingUnitSpec spec) {
        if (spec != null) {
            BigDecimal size = size(spec);
            if (size != null && size.compareTo(BigDecimal.ONE) != 0) {
                throw new BusinessRuleViolation(
                        CatalogErrorCode.INVALID_QTY_IN_BASE,
                        "The base unit's quantity in base units is 1",
                        Map.of("unitCode", spec.code(), "qtyInBase", size.toPlainString()));
            }
        }
        boolean canBuy =
                flag(spec != null ? spec.canBuy() : null, strategy.defaults().baseCanBuy());
        boolean canSell =
                flag(spec != null ? spec.canSell() : null, strategy.defaults().baseCanSell());
        String state = spec != null && spec.stockState() != null
                ? spec.stockState()
                : strategy.defaultStockState(new UnitSpec(baseUnit.code(), true, canBuy, canSell));
        return new PackagingUnitDraft(
                baseUnit.code(),
                spec != null ? spec.name() : baseUnit.name(),
                BigDecimal.ONE,
                null,
                state,
                true,
                canBuy,
                canSell,
                spec != null && Boolean.TRUE.equals(spec.isDefaultSell()));
    }

    /** A non-base unit; exactly one of {@code qtyInBase} and {@code definedAs}. */
    PackagingUnitDraft other(PackagingUnitSpec spec) {
        boolean canBuy = flag(spec.canBuy(), false);
        boolean canSell = flag(spec.canSell(), false);
        DefinedQuantity defined = defined(spec.definedAs());
        BigDecimal qty = spec.qtyInBase();
        if ((qty == null) == (defined == null)) {
            throw new ValidationFailure(List.of(new FieldError(
                    "packagingUnits", "ONE_OF", "give exactly one of qtyInBase and definedAs for " + spec.code())));
        }
        BigDecimal qtyInBase = qty != null ? qty : toBase(defined);
        String state = spec.stockState() != null
                ? spec.stockState()
                : strategy.defaultStockState(new UnitSpec(spec.code(), false, canBuy, canSell));
        return new PackagingUnitDraft(
                spec.code(),
                spec.name(),
                qtyInBase,
                defined,
                state,
                false,
                canBuy,
                canSell,
                Boolean.TRUE.equals(spec.isDefaultSell()));
    }

    /** Copies of a variant's non-base units for a new sibling variant (CAT-05 regeneration, manual variants). */
    static List<PackagingUnitDraft> copiesOf(Variant variant) {
        List<PackagingUnitDraft> drafts = new ArrayList<>();
        for (PackagingUnit unit : variant.packagingUnits()) {
            if (unit.isActive()) {
                drafts.add(new PackagingUnitDraft(
                        unit.code(),
                        unit.name(),
                        unit.qtyInBase(),
                        unit.definedAs(),
                        unit.stockState(),
                        unit.isBase(),
                        unit.canBuy(),
                        unit.canSell(),
                        unit.isDefaultSell()));
            }
        }
        return drafts;
    }

    /** Base units of a quantity defined in another unit, under the product's conversions (CAT-03, CAT-14). */
    BigDecimal toBase(DefinedQuantity defined) {
        return converter.convert(defined.value(), defined.unitId(), baseUnit.id());
    }

    @Nullable
    DefinedQuantity defined(PackagingUnitSpec.@Nullable DefinedAsSpec spec) {
        if (spec == null) {
            return null;
        }
        return new DefinedQuantity(spec.value(), directory.byCode(spec.unit()).id());
    }

    private @Nullable BigDecimal size(PackagingUnitSpec spec) {
        DefinedQuantity defined = defined(spec.definedAs());
        return defined != null ? toBase(defined) : spec.qtyInBase();
    }

    private Optional<PackagingUnitSpec> baseSpec(List<PackagingUnitSpec> specs) {
        return specs.stream().filter(this::isBase).findFirst();
    }

    private boolean isBase(PackagingUnitSpec spec) {
        return spec.code().equalsIgnoreCase(baseUnit.code());
    }

    private static boolean flag(@Nullable Boolean requested, boolean fallback) {
        return requested != null ? requested : fallback;
    }
}
