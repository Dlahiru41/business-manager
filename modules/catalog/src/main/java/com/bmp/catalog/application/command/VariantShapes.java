package com.bmp.catalog.application.command;

import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.domain.model.PackagingUnitDraft;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.service.ProductTemplateApplier;
import com.bmp.catalog.domain.service.TemplateStrategy;
import com.bmp.catalog.domain.service.UnitConverter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides the packaging units, stock states and sell mode of a variant added to an existing product: those of its
 * oldest active variant, else the product template defaults with only the base unit.
 */
@Component
class VariantShapes {

    VariantFactory.Shape forNewVariant(Product product, List<Variant> siblings, UnitDirectory directory) {
        Optional<Variant> model =
                siblings.stream().filter(Variant::isActive).min(Comparator.comparing(Variant::createdAt));
        if (model.isPresent()) {
            Variant sibling = model.get();
            return new VariantFactory.Shape(
                    PackagingDrafts.copiesOf(sibling), sibling.stockStates(), sibling.sellMode());
        }
        return fromTemplate(product, List.of(), null, null, directory);
    }

    /** The shape of a variant described by the request, with template defaults for what it leaves out. */
    VariantFactory.Shape fromTemplate(
            Product product,
            List<PackagingUnitSpec> units,
            @Nullable List<String> stockStates,
            @Nullable SellMode sellMode,
            UnitDirectory directory) {
        TemplateStrategy strategy = ProductTemplateApplier.strategy(product.template());
        PackagingDrafts drafts = drafts(product, directory);
        List<PackagingUnitDraft> all = drafts.all(units);
        return new VariantFactory.Shape(
                all,
                stockStates != null ? stockStates : strategy.stockStates(drafts.unitSpecs(units)),
                sellMode != null ? sellMode : strategy.defaults().sellMode());
    }

    /** Draft building for the product: its template, base unit and conversions. */
    PackagingDrafts drafts(Product product, UnitDirectory directory) {
        return new PackagingDrafts(
                ProductTemplateApplier.strategy(product.template()),
                directory.all().stream()
                        .filter(u -> u.id().equals(product.baseUnitId()))
                        .findFirst()
                        .orElseThrow(),
                directory,
                new UnitConverter(directory.all(), product.conversions()));
    }
}
