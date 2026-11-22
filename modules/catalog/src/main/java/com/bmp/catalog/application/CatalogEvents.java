package com.bmp.catalog.application;

import com.bmp.catalog.api.events.PackagingUnitSnapshot;
import com.bmp.catalog.api.events.ProductCreated;
import com.bmp.catalog.api.events.ProductTracking;
import com.bmp.catalog.api.events.ProductUpdated;
import com.bmp.catalog.api.events.TransformationDefinitionPublished;
import com.bmp.catalog.api.events.TransformationDefinitionRetired;
import com.bmp.catalog.api.events.TransformationLineSnapshot;
import com.bmp.catalog.api.events.VariantAttributeValue;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.catalog.api.events.VariantDeactivated;
import com.bmp.catalog.api.events.VariantUpdated;
import com.bmp.catalog.domain.model.AttributeValue;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.Tracking;
import com.bmp.catalog.domain.model.TransformationDefinition;
import com.bmp.catalog.domain.model.TransformationLine;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the catalog events (CAT-13). Events carry full snapshots built from the saved, flushed aggregates, so
 * {@code aggregateVersion} is the version just stored (ADR-019). Called by handlers inside their transaction; the
 * outbox delivers after commit.
 */
@Component
public class CatalogEvents {

    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;

    public CatalogEvents(ApplicationEventPublisher publisher, EventStampSource stamps) {
        this.publisher = publisher;
        this.stamps = stamps;
    }

    public void productCreated(Product product, UnitDirectory directory) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new ProductCreated(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                product.id().value(),
                product.version(),
                stamp.metadata(),
                product.id(),
                product.code(),
                product.name(),
                product.template().name(),
                directory.codeOf(product.baseUnitId()),
                tracking(product.tracking()),
                product.categoryId(),
                product.taxCategoryId(),
                product.status().name()));
    }

    public void productUpdated(Product product, UnitDirectory directory) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new ProductUpdated(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                product.id().value(),
                product.version(),
                stamp.metadata(),
                product.id(),
                product.code(),
                product.name(),
                product.template().name(),
                directory.codeOf(product.baseUnitId()),
                tracking(product.tracking()),
                product.categoryId(),
                product.taxCategoryId(),
                product.status().name()));
    }

    public void variantCreated(Variant variant, Product product, UnitDirectory directory) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new VariantCreated(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                variant.id().value(),
                variant.version(),
                stamp.metadata(),
                variant.id(),
                variant.productId(),
                variant.sku(),
                variant.name(),
                attributeValues(variant, product),
                variant.stockStates(),
                variant.sellMode().name(),
                variant.status().name(),
                directory.codeOf(product.baseUnitId()),
                product.taxCategoryId(),
                tracking(product.tracking()),
                units(variant)));
    }

    public void variantUpdated(Variant variant, Product product, UnitDirectory directory) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new VariantUpdated(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                variant.id().value(),
                variant.version(),
                stamp.metadata(),
                variant.id(),
                variant.productId(),
                variant.sku(),
                variant.name(),
                attributeValues(variant, product),
                variant.stockStates(),
                variant.sellMode().name(),
                variant.status().name(),
                directory.codeOf(product.baseUnitId()),
                product.taxCategoryId(),
                tracking(product.tracking()),
                units(variant)));
    }

    public void variantDeactivated(Variant variant) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new VariantDeactivated(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                variant.id().value(),
                variant.version(),
                stamp.metadata(),
                variant.id(),
                variant.productId(),
                variant.sku()));
    }

    public void definitionPublished(TransformationDefinition definition) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new TransformationDefinitionPublished(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                definition.id(),
                definition.version(),
                stamp.metadata(),
                definition.id(),
                definition.defVersion(),
                definition.code(),
                definition.name(),
                definition.kind().name(),
                definition.expectedYieldPct(),
                lines(definition)));
    }

    public void definitionRetired(TransformationDefinition definition) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new TransformationDefinitionRetired(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                definition.id(),
                definition.version(),
                stamp.metadata(),
                definition.id(),
                definition.defVersion()));
    }

    /** The lines of a definition as events and the frozen version log carry them. */
    public static List<TransformationLineSnapshot> lines(TransformationDefinition definition) {
        return definition.lines().stream().map(CatalogEvents::line).toList();
    }

    private static TransformationLineSnapshot line(TransformationLine line) {
        UUID unitId = line.packagingUnitId();
        return new TransformationLineSnapshot(
                line.direction().name(),
                VariantId.of(line.variantId()),
                line.stockState(),
                line.qtyBase(),
                unitId != null ? PackagingUnitId.of(unitId) : null,
                line.costSharePct());
    }

    private static List<VariantAttributeValue> attributeValues(Variant variant, Product product) {
        return product.valuesOf(variant.attributeValueIds()).stream()
                .map(value -> attributeValue(product, value))
                .toList();
    }

    private static VariantAttributeValue attributeValue(Product product, AttributeValue value) {
        String attributeCode =
                product.attributeOf(value.id()).map(a -> a.code()).orElseThrow();
        return new VariantAttributeValue(attributeCode, value.code(), value.value());
    }

    private static List<PackagingUnitSnapshot> units(Variant variant) {
        return variant.packagingUnits().stream().map(CatalogEvents::unit).toList();
    }

    private static PackagingUnitSnapshot unit(PackagingUnit unit) {
        return new PackagingUnitSnapshot(
                PackagingUnitId.of(unit.id()),
                unit.code(),
                unit.name(),
                unit.qtyInBase(),
                unit.stockState(),
                unit.isBase(),
                unit.canBuy(),
                unit.canSell(),
                unit.isDefaultSell(),
                unit.status().name(),
                unit.barcodes());
    }

    private static ProductTracking tracking(Tracking tracking) {
        return new ProductTracking(
                tracking.batches(), tracking.expiry(), tracking.serials(), tracking.decimalQty(), tracking.weighed());
    }
}
