package com.bmp.catalog.domain.service;

import com.bmp.catalog.domain.model.LineDraft;
import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.Tracking;
import com.bmp.catalog.domain.model.TransformationKind;
import com.bmp.catalog.domain.model.Variant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The defaults of one product template (CAT-09, domain-model.md §2). Templates only set defaults: everything they set
 * can be overridden in the create request or edited afterwards.
 */
public sealed interface TemplateStrategy permits StandardTemplate, BulkBreakTemplate, LooseRepackTemplate {

    ProductTemplate template();

    TemplateDefaults defaults();

    /** The stock states of a new variant with these packaging units (base unit included). */
    List<String> stockStates(List<UnitSpec> units);

    /** The state a packaging unit draws from when the request names none. */
    String defaultStockState(UnitSpec unit);

    /** The transformation definitions the template implies for a new variant, to be published at once. */
    List<DefinitionPlan> transformations(String productCode, Variant variant);

    /**
     * Static defaults of a template.
     *
     * @param name display name
     * @param description what the template is for
     * @param attributesRequired variants come from an attribute matrix that must be given
     * @param baseUnitCode default base unit
     * @param stockStates default stock states of a variant
     * @param sellMode default sell mode
     * @param tracksStock whether stock is kept at all (not for services)
     * @param autoTransformations kinds of definitions created automatically
     * @param tracking default tracking flags
     * @param baseCanBuy the base unit may be bought
     * @param baseCanSell the base unit may be sold
     */
    record TemplateDefaults(
            String name,
            String description,
            boolean attributesRequired,
            String baseUnitCode,
            List<String> stockStates,
            SellMode sellMode,
            boolean tracksStock,
            List<TransformationKind> autoTransformations,
            Tracking tracking,
            boolean baseCanBuy,
            boolean baseCanSell) {

        public TemplateDefaults {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(baseUnitCode, "baseUnitCode");
            stockStates = List.copyOf(stockStates);
            Objects.requireNonNull(sellMode, "sellMode");
            autoTransformations = List.copyOf(autoTransformations);
            Objects.requireNonNull(tracking, "tracking");
        }
    }

    /**
     * What a template needs to know of a requested packaging unit.
     *
     * @param code the unit code
     * @param isBase whether it is the base unit
     * @param canBuy may be bought
     * @param canSell may be sold
     */
    record UnitSpec(String code, boolean isBase, boolean canBuy, boolean canSell) {}

    /**
     * A transformation definition implied by a template.
     *
     * @param code unique per tenant
     * @param name display name
     * @param kind what it does
     * @param expectedYieldPct expected output per input, in percent
     * @param lines its lines
     */
    record DefinitionPlan(
            String code, String name, TransformationKind kind, BigDecimal expectedYieldPct, List<LineDraft> lines) {

        public DefinitionPlan {
            lines = List.copyOf(lines);
        }
    }
}
