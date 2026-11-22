package com.bmp.catalog.domain.service;

import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.StockStates;
import com.bmp.catalog.domain.model.Variant;
import java.util.List;

/**
 * Templates whose variants keep all stock in {@code DEFAULT} and imply no transformation: {@code SIMPLE},
 * {@code VARIANT_MATRIX}, {@code BRANDED_PACKET}, {@code RECIPE} and {@code SERVICE}.
 *
 * @param template the template
 * @param defaults its defaults
 */
public record StandardTemplate(ProductTemplate template, TemplateDefaults defaults) implements TemplateStrategy {

    @Override
    public List<String> stockStates(List<UnitSpec> units) {
        return defaults.stockStates();
    }

    @Override
    public String defaultStockState(UnitSpec unit) {
        return StockStates.DEFAULT;
    }

    @Override
    public List<DefinitionPlan> transformations(String productCode, Variant variant) {
        return List.of();
    }
}
