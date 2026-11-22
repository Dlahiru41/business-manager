package com.bmp.catalog.application.query;

import com.bmp.catalog.domain.service.TemplateStrategy;
import java.util.List;

/** A product template with its defaults (CAT-09). */
public record TemplateView(
        String code,
        String name,
        String description,
        boolean attributesRequired,
        String defaultBaseUnit,
        List<String> defaultStockStates,
        String defaultSellMode,
        boolean tracksStock,
        List<String> autoTransformations) {

    public TemplateView {
        defaultStockStates = List.copyOf(defaultStockStates);
        autoTransformations = List.copyOf(autoTransformations);
    }

    public static TemplateView of(TemplateStrategy strategy) {
        var defaults = strategy.defaults();
        return new TemplateView(
                strategy.template().name(),
                defaults.name(),
                defaults.description(),
                defaults.attributesRequired(),
                defaults.baseUnitCode(),
                defaults.stockStates(),
                defaults.sellMode().name(),
                defaults.tracksStock(),
                defaults.autoTransformations().stream().map(Enum::name).toList());
    }
}
