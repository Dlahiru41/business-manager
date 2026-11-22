package com.bmp.catalog.application.command;

import com.bmp.catalog.domain.model.AttributeValue;
import com.bmp.catalog.domain.model.NewVariant;
import com.bmp.catalog.domain.model.PackagingUnitDraft;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.catalog.domain.service.SkuGenerator;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.VariantId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Creates and stores the variants of a product: SKUs generated per CAT-06 and de-duplicated against the tenant and
 * the batch, names from the product name and the attribute values, the same packaging units for each new variant.
 */
@Component
class VariantFactory {

    private final VariantRepository variants;
    private final IdGenerator ids;

    VariantFactory(VariantRepository variants, IdGenerator ids) {
        this.variants = variants;
        this.ids = ids;
    }

    /** What the new variants share. */
    record Shape(List<PackagingUnitDraft> units, List<String> stockStates, SellMode sellMode) {
        Shape {
            units = List.copyOf(units);
            stockStates = List.copyOf(stockStates);
        }
    }

    /** Creates one variant per combination (one variant without attributes for an empty combination). */
    List<Variant> create(Product product, List<List<UUID>> combinations, Shape shape, long revision) {
        Set<String> taken = new HashSet<>(variants.skusStartingWith(product.code()));
        List<Variant> created = new ArrayList<>();
        for (List<UUID> combination : combinations) {
            created.add(create(product, combination, null, null, shape, revision, taken));
        }
        return created;
    }

    /** Creates one variant, with an optional SKU and name override. */
    Variant createOne(
            Product product,
            List<UUID> combination,
            @Nullable String sku,
            @Nullable String name,
            Shape shape,
            long revision) {
        return create(
                product,
                combination,
                sku,
                name,
                shape,
                revision,
                new HashSet<>(variants.skusStartingWith(product.code())));
    }

    /** The SKU a combination would get now (dry runs). */
    String skuFor(Product product, List<UUID> combination, Set<String> taken) {
        return SkuGenerator.generate(product.code(), valueCodes(product, combination), taken::contains);
    }

    static String nameFor(Product product, List<UUID> combination) {
        List<AttributeValue> values = product.valuesOf(combination);
        if (values.isEmpty()) {
            return product.name();
        }
        return product.name() + " " + values.stream().map(AttributeValue::value).collect(Collectors.joining(" "));
    }

    private Variant create(
            Product product,
            List<UUID> combination,
            @Nullable String sku,
            @Nullable String name,
            Shape shape,
            long revision,
            Set<String> taken) {
        String chosenSku = sku != null ? sku : skuFor(product, combination, taken);
        taken.add(chosenSku.toUpperCase(java.util.Locale.ROOT));
        Variant variant = Variant.create(
                ids.next(VariantId::new),
                TenantContext.require(),
                product.id(),
                new NewVariant(
                        chosenSku,
                        name != null ? name : nameFor(product, combination),
                        combination,
                        shape.stockStates(),
                        shape.sellMode(),
                        shape.units()),
                ids::newUuid);
        variant.touch(revision);
        return variants.saveAndFlush(variant);
    }

    private static List<String> valueCodes(Product product, List<UUID> combination) {
        return product.valuesOf(combination).stream().map(AttributeValue::code).toList();
    }
}
