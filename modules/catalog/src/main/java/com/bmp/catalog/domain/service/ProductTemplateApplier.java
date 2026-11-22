package com.bmp.catalog.domain.service;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.StockStates;
import com.bmp.catalog.domain.model.Tracking;
import com.bmp.catalog.domain.model.TransformationKind;
import com.bmp.catalog.domain.service.TemplateStrategy.TemplateDefaults;
import com.bmp.shared.error.BusinessRuleViolation;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Applies product templates (CAT-09): one {@link TemplateStrategy} per template with the defaults of
 * domain-model.md §2. Adding a template means adding a strategy here; no product type is hard-coded anywhere else.
 */
public final class ProductTemplateApplier {

    private static final Map<ProductTemplate, TemplateStrategy> STRATEGIES = new EnumMap<>(ProductTemplate.class);

    static {
        List<String> defaultState = List.of(StockStates.DEFAULT);
        register(new StandardTemplate(
                ProductTemplate.SIMPLE,
                new TemplateDefaults(
                        "Simple",
                        "One variant counted in pieces, no attributes.",
                        false,
                        "piece",
                        defaultState,
                        SellMode.PREPACKED,
                        true,
                        List.of(),
                        Tracking.counted(),
                        true,
                        true)));
        register(new StandardTemplate(
                ProductTemplate.VARIANT_MATRIX,
                new TemplateDefaults(
                        "Variant matrix",
                        "Variants are the cartesian product of attribute values minus exclusions.",
                        true,
                        "piece",
                        defaultState,
                        SellMode.PREPACKED,
                        true,
                        List.of(),
                        Tracking.counted(),
                        true,
                        true)));
        register(new BulkBreakTemplate(new TemplateDefaults(
                "Bulk break",
                "One variant with states SEALED/OPEN; the bulk unit is bought sealed and sold opened.",
                false,
                "ml",
                List.of(StockStates.SEALED, StockStates.OPEN),
                SellMode.BREAK_ON_SALE,
                true,
                List.of(TransformationKind.OPEN),
                Tracking.measured(),
                false,
                false)));
        register(new StandardTemplate(
                ProductTemplate.BRANDED_PACKET,
                new TemplateDefaults(
                        "Branded packet",
                        "Variants per brand (and packet size), counted in packets.",
                        true,
                        "pack",
                        defaultState,
                        SellMode.PREPACKED,
                        true,
                        List.of(),
                        Tracking.counted(),
                        true,
                        true)));
        register(new LooseRepackTemplate(new TemplateDefaults(
                "Loose repack",
                "Loose goods by weight, repacked into the shop's own packets kept in their own stock state.",
                false,
                "g",
                defaultState,
                SellMode.PREPACKED,
                true,
                List.of(TransformationKind.REPACK),
                Tracking.weighedLoose(),
                true,
                true)));
        register(new StandardTemplate(
                ProductTemplate.RECIPE,
                new TemplateDefaults(
                        "Recipe",
                        "Outputs made from input variants through a RECIPE definition created afterwards.",
                        false,
                        "piece",
                        defaultState,
                        SellMode.PREPACKED,
                        true,
                        List.of(),
                        Tracking.counted(),
                        false,
                        true)));
        register(new StandardTemplate(
                ProductTemplate.SERVICE,
                new TemplateDefaults(
                        "Service",
                        "No stock is kept (delivery charge).",
                        false,
                        "piece",
                        defaultState,
                        SellMode.PREPACKED,
                        false,
                        List.of(),
                        Tracking.counted(),
                        false,
                        true)));
    }

    private ProductTemplateApplier() {}

    public static TemplateStrategy strategy(ProductTemplate template) {
        TemplateStrategy strategy = STRATEGIES.get(template);
        if (strategy == null) {
            throw new IllegalStateException("No strategy for template " + template);
        }
        return strategy;
    }

    /** Every template, in declaration order. */
    public static List<TemplateStrategy> all() {
        return List.copyOf(STRATEGIES.values());
    }

    /**
     * Rule CAT-09: whether a product of this template, with or without attributes, is valid.
     *
     * @throws BusinessRuleViolation {@code ATTRIBUTES_REQUIRED} for matrix templates without attributes
     */
    public static void requireAttributes(ProductTemplate template, boolean hasAttributes) {
        if (strategy(template).defaults().attributesRequired() && !hasAttributes) {
            throw new BusinessRuleViolation(CatalogErrorCode.ATTRIBUTES_REQUIRED, Map.of("template", template));
        }
    }

    private static void register(TemplateStrategy strategy) {
        STRATEGIES.put(strategy.template(), strategy);
    }
}
